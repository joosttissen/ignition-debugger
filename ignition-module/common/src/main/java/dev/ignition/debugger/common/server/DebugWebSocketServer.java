package dev.ignition.debugger.common.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import dev.ignition.debugger.common.protocol.JsonRpcNotification;
import dev.ignition.debugger.common.protocol.JsonRpcResponse;
import dev.ignition.debugger.common.debug.BreakpointManager;
import dev.ignition.debugger.common.debug.JythonDebugger;
import dev.ignition.debugger.common.debug.JythonDebugger.FrameInfo;
import dev.ignition.debugger.common.debug.JythonDebugger.VariableInfo;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.python.core.*;
import org.python.util.PythonInterpreter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * WebSocket server embedded in an Ignition module scope (Designer or Gateway).
 *
 * <p>Listens on {@code 0.0.0.0:{port}} and handles JSON-RPC 2.0 requests
 * from the VS Code extension.  One client connection is expected at a time.
 *
 * <h2>Supported methods</h2>
 * <ul>
 *   <li>{@code authenticate} – verify the shared secret</li>
 *   <li>{@code ping} – connectivity check</li>
 *   <li>{@code debug.startSession} – compile and prepare a script for debugging</li>
 *   <li>{@code debug.run} – start executing the script (after breakpoints are set)</li>
 *   <li>{@code debug.stopSession} – abort the current session</li>
 *   <li>{@code debug.setBreakpoints} – set breakpoints for a file</li>
 *   <li>{@code debug.getStackTrace} – return stack frames at the current pause</li>
 *   <li>{@code debug.getScopes} – return variable scopes for a frame</li>
 *   <li>{@code debug.getVariables} – return variables for a scope</li>
 *   <li>{@code debug.continue} – resume execution</li>
 *   <li>{@code debug.stepOver} – step over the current line</li>
 *   <li>{@code debug.stepInto} – step into the next call</li>
 *   <li>{@code debug.stepOut} – step out of the current frame</li>
 *   <li>{@code debug.pause} – request an async pause (best-effort)</li>
 *   <li>{@code debug.evaluate} – evaluate an expression in the current frame</li>
 *   <li>{@code debug.attach} – attach to running gateway scripts for debugging</li>
 *   <li>{@code debug.detach} – detach from the running gateway and stop debugging</li>
 * </ul>
 */
public class DebugWebSocketServer extends WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(DebugWebSocketServer.class);

    private static final int MAX_CLIENTS = 1;

    private final String sharedSecret;
    private final ObjectMapper mapper = new ObjectMapper();

    private WebSocket authenticatedClient = null;

    /** Active debug sessions keyed by sessionId. */
    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    /**
     * Optional resolver that returns the correct {@link ScriptManager} for a
     * given project name.  When set, debug sessions use
     * {@code ScriptManager.runCode()} so that the full Ignition scripting
     * environment (including the {@code system} API) is available.
     *
     * <p>In the Gateway scope this is wired to
     * {@code ProjectManager::getProjectScriptManager} so that each session
     * gets the per-project manager that has {@code system.*} registered.
     */
    private volatile Function<String, ScriptManager> scriptManagerResolver = null;

    /** Provide a resolver that maps a project name to its {@link ScriptManager}. */
    public void setScriptManagerResolver(Function<String, ScriptManager> resolver) {
        this.scriptManagerResolver = resolver;
    }

    // ---- Construction / lifecycle -----------------------------------------

    /**
     * @param port   TCP port to listen on (0 = auto-assign)
     * @param secret shared secret for authentication
     */
    public DebugWebSocketServer(int port, String secret) {
        super(new InetSocketAddress("0.0.0.0", port));
        this.sharedSecret = secret;
        setReuseAddr(true);
        setConnectionLostTimeout(60);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        log.info("Client connected: {}", conn.getRemoteSocketAddress());
        if (getConnections().size() > MAX_CLIENTS) {
            conn.close(1008, "Only one client allowed");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (conn.equals(authenticatedClient)) {
            log.info("Debug client disconnected");
            authenticatedClient = null;
            // Stop any active sessions
            for (DebugSession session : sessions.values()) {
                session.abort();
            }
            sessions.clear();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);
            Object id = null;
            JsonNode idNode = msg.get("id");
            if (idNode != null && !idNode.isNull()) {
                id = idNode.isInt() ? idNode.intValue() : idNode.asText();
            }
            String method = msg.path("method").asText(null);
            JsonNode params = msg.get("params");

            if (method == null) {
                sendError(conn, id, -32600, "Invalid Request: missing method");
                return;
            }

            // Authentication check (only 'authenticate' is allowed unauthenticated)
            if (!"authenticate".equals(method) && !conn.equals(authenticatedClient)) {
                sendError(conn, id, -32001, "Not authenticated");
                return;
            }

            handleMethod(conn, id, method, params);

        } catch (Exception e) {
            log.error("Error handling WebSocket message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("WebSocket error: {}", ex.getMessage(), ex);
    }

    @Override
    public void onStart() {
        log.info("Ignition Debugger WebSocket server started on port {}", getPort());
    }

    // ---- Method dispatch ---------------------------------------------------

    private void handleMethod(WebSocket conn, Object id, String method, JsonNode params)
            throws Exception {
        switch (method) {
            case "authenticate":
                handleAuthenticate(conn, id, params);
                break;
            case "ping":
                handlePing(conn, id);
                break;
            case "debug.startSession":
                handleStartSession(conn, id, params);
                break;
            case "debug.run":
                handleRun(conn, id, params);
                break;
            case "debug.stopSession":
                handleStopSession(conn, id, params);
                break;
            case "debug.setBreakpoints":
                handleSetBreakpoints(conn, id, params);
                break;
            case "debug.getStackTrace":
                handleGetStackTrace(conn, id, params);
                break;
            case "debug.getScopes":
                handleGetScopes(conn, id, params);
                break;
            case "debug.getVariables":
                handleGetVariables(conn, id, params);
                break;
            case "debug.continue":
                handleDebugCommand(conn, id, params, "continue");
                break;
            case "debug.stepOver":
                handleDebugCommand(conn, id, params, "stepOver");
                break;
            case "debug.stepInto":
                handleDebugCommand(conn, id, params, "stepIn");
                break;
            case "debug.stepOut":
                handleDebugCommand(conn, id, params, "stepOut");
                break;
            case "debug.pause":
                handleDebugCommand(conn, id, params, "pause");
                break;
            case "debug.evaluate":
                handleEvaluate(conn, id, params);
                break;
            case "debug.attach":
                handleAttach(conn, id, params);
                break;
            case "debug.detach":
                handleDetach(conn, id, params);
                break;
            default:
                sendError(conn, id, -32601, "Method not found: " + method);
        }
    }

    // ---- Handlers ----------------------------------------------------------

    private void handleAuthenticate(WebSocket conn, Object id, JsonNode params) {
        String secret = params != null ? params.path("secret").asText("") : "";
        if (sharedSecret.equals(secret)) {
            authenticatedClient = conn;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("moduleVersion", "0.1.0");
            sendResult(conn, id, result);
        } else {
            Map<String, Object> result = Map.of("success", false, "error", "Invalid secret");
            sendResult(conn, id, result);
        }
    }

    private void handlePing(WebSocket conn, Object id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("timestamp", System.currentTimeMillis());
        sendResult(conn, id, result);
    }

    private void handleStartSession(WebSocket conn, Object id, JsonNode params) {
        if (params == null) {
            sendError(conn, id, -32602, "Missing params");
            return;
        }
        String code = params.path("code").asText("");
        String filePath = params.path("filePath").asText("<script>");

        // Resolve the ScriptManager for this session.
        // projectName may be sent explicitly; if not, extract it from the filePath.
        String projectName = params.path("projectName").asText("");
        if (projectName.isEmpty()) {
            projectName = extractProjectName(filePath);
        }

        ScriptManager sessionScriptManager = null;
        if (scriptManagerResolver != null && !projectName.isEmpty()) {
            try {
                sessionScriptManager = scriptManagerResolver.apply(projectName);
                if (sessionScriptManager != null) {
                    log.debug("Resolved ScriptManager for project '{}'", projectName);
                } else {
                    log.warn("ScriptManager resolver returned null for project '{}'", projectName);
                }
            } catch (Exception ex) {
                log.warn("Could not resolve ScriptManager for project '{}': {}", projectName, ex.getMessage());
            }
        }

        String sessionId = "session-" + sessionCounter.incrementAndGet();
        DebugSession session = new DebugSession(sessionId, code, filePath, sessionScriptManager,
                (event, body) -> sendNotification(conn, "debug.event." + event, body));
        sessions.put(sessionId, session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        sendResult(conn, id, result);
    }

    /**
     * Extract the Ignition project name from a file path.
     *
     * <p>Ignition project files live at paths like:
     * {@code .../projects/<projectName>/com.inductiveautomation.ignition.designer.scripting.ScriptDesignableWorkspace/...}
     *
     * <p>This method looks for a {@code projects/} segment and returns the next
     * path component.
     */
    private static String extractProjectName(String filePath) {
        if (filePath == null || filePath.isEmpty()) return "";
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.lastIndexOf("/projects/");
        if (idx < 0) return "";
        String after = normalized.substring(idx + "/projects/".length());
        int slash = after.indexOf('/');
        return slash < 0 ? after : after.substring(0, slash);
    }

    private void handleRun(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendError(conn, id, -32002, "Session not found: " + sessionId);
            return;
        }
        sendResult(conn, id, Map.of("success", true));
        // Execute the script in a background thread so we don't block the WS thread
        session.execute();
    }

    private void handleStopSession(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        DebugSession session = sessions.remove(sessionId);
        if (session != null) {
            session.abort();
        }
        sendResult(conn, id, Map.of("success", true));
    }

    private void handleSetBreakpoints(WebSocket conn, Object id, JsonNode params) throws Exception {
        if (params == null) {
            sendError(conn, id, -32602, "Missing params");
            return;
        }
        String sessionId = params.path("sessionId").asText("");
        String filePath = params.path("filePath").asText("");
        JsonNode bpArray = params.get("breakpoints");

        List<BreakpointManager.BreakpointRequest> requests = new ArrayList<>();
        if (bpArray != null && bpArray.isArray()) {
            for (JsonNode bp : bpArray) {
                int line = bp.path("line").asInt(0);
                String condition = bp.path("condition").asText(null);
                requests.add(new BreakpointManager.BreakpointRequest(line, condition));
            }
        }

        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendError(conn, id, -32002, "Session not found: " + sessionId);
            return;
        }

        List<BreakpointManager.Breakpoint> bps = session.setBreakpoints(filePath, requests);
        List<Map<String, Object>> bpResults = new ArrayList<>();
        for (BreakpointManager.Breakpoint bp : bps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", bp.id);
            m.put("verified", bp.verified);
            m.put("line", bp.line);
            bpResults.add(m);
        }
        sendResult(conn, id, Map.of("breakpoints", bpResults));
    }

    private void handleGetStackTrace(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendResult(conn, id, Map.of("stackFrames", List.of(), "totalFrames", 0));
            return;
        }

        List<FrameInfo> frames = session.getDebugger().getCapturedFrames();
        List<Map<String, Object>> frameList = new ArrayList<>();
        for (FrameInfo f : frames) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id);
            fm.put("name", f.name);
            fm.put("filePath", f.filePath);
            fm.put("line", f.line);
            fm.put("column", 0);
            frameList.add(fm);
        }
        sendResult(conn, id, Map.of("stackFrames", frameList, "totalFrames", frameList.size()));
    }

    private void handleGetScopes(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        int frameId = params != null ? params.path("frameId").asInt(0) : 0;

        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendResult(conn, id, Map.of("scopes", List.of()));
            return;
        }

        List<FrameInfo> frames = session.getDebugger().getCapturedFrames();
        if (frameId >= frames.size()) {
            sendResult(conn, id, Map.of("scopes", List.of()));
            return;
        }
        FrameInfo fi = frames.get(frameId);

        List<Map<String, Object>> scopes = new ArrayList<>();
        Map<String, Object> locals = new LinkedHashMap<>();
        locals.put("name", "Locals");
        locals.put("variablesReference", fi.localsRef);
        locals.put("expensive", false);
        scopes.add(locals);

        Map<String, Object> globals = new LinkedHashMap<>();
        globals.put("name", "Globals");
        globals.put("variablesReference", fi.globalsRef);
        globals.put("expensive", true);
        scopes.add(globals);

        sendResult(conn, id, Map.of("scopes", scopes));
    }

    private void handleGetVariables(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        int varRef = params != null ? params.path("variablesReference").asInt(0) : 0;

        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendResult(conn, id, Map.of("variables", List.of()));
            return;
        }

        Map<String, Object> rawVars = session.getDebugger().getVariables(varRef);
        List<Map<String, Object>> variables = new ArrayList<>();
        for (Map.Entry<String, Object> entry : rawVars.entrySet()) {
            VariableInfo vi = (VariableInfo) entry.getValue();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", entry.getKey());
            v.put("value", vi.value);
            v.put("type", vi.type);
            v.put("variablesReference", vi.variablesReference);
            variables.add(v);
        }
        sendResult(conn, id, Map.of("variables", variables));
    }

    private void handleDebugCommand(WebSocket conn, Object id, JsonNode params, String command) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendError(conn, id, -32002, "Session not found: " + sessionId);
            return;
        }
        JythonDebugger dbg = session.getDebugger();
        switch (command) {
            case "continue": dbg.resume();   break;
            case "stepOver": dbg.stepOver(); break;
            case "stepIn":   dbg.stepIn();   break;
            case "stepOut":  dbg.stepOut();  break;
            case "pause":    /* best-effort – sets STEP_IN so next line pauses */
                dbg.stepIn(); break;
            default:
                sendError(conn, id, -32601, "Unknown command: " + command);
                return;
        }
        sendResult(conn, id, Map.of("success", true));
    }

    private void handleEvaluate(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        String expression = params != null ? params.path("expression").asText("") : "";
        int frameId = params != null ? params.path("frameId").asInt(0) : 0;

        DebugSession session = sessions.get(sessionId);
        if (session == null) {
            sendError(conn, id, -32002, "Session not found");
            return;
        }

        String result = session.getDebugger().evaluate(expression, frameId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", result);
        r.put("type", "string");
        r.put("variablesReference", 0);
        sendResult(conn, id, r);
    }

    /**
     * Handle {@code debug.attach} – attach the debugger to gateway script execution.
     *
     * <p>Instead of running a specific script, this installs the trace function
     * globally so that any script executed by the gateway's ScriptManager is
     * subject to breakpoint checks.  The trace function is installed via
     * {@code sys.settrace()} on the ScriptManager's execution thread and via
     * {@code threading.settrace()} for any new threads created by Jython.
     */
    private void handleAttach(WebSocket conn, Object id, JsonNode params) {
        String projectName = params != null ? params.path("projectName").asText("") : "";

        ScriptManager sessionScriptManager = null;
        if (scriptManagerResolver != null && !projectName.isEmpty()) {
            try {
                sessionScriptManager = scriptManagerResolver.apply(projectName);
            } catch (Exception ex) {
                log.warn("Could not resolve ScriptManager for project '{}': {}", projectName, ex.getMessage());
            }
        }

        String sessionId = "attach-" + sessionCounter.incrementAndGet();
        DebugSession session = DebugSession.createAttachSession(sessionId, sessionScriptManager,
                (event, body) -> sendNotification(conn, "debug.event." + event, body));
        sessions.put(sessionId, session);

        try {
            session.installAttach();
        } catch (Exception e) {
            log.warn("Could not install attach-mode trace (ScriptManager may not be available): {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        sendResult(conn, id, result);
    }

    /**
     * Handle {@code debug.detach} – detach from gateway script execution.
     *
     * <p>Removes the globally installed trace function and cleans up the
     * attach session.
     */
    private void handleDetach(WebSocket conn, Object id, JsonNode params) {
        String sessionId = params != null ? params.path("sessionId").asText("") : "";
        DebugSession session = sessions.remove(sessionId);
        if (session != null) {
            session.abort();
        }
        sendResult(conn, id, Map.of("success", true));
    }

    // ---- Messaging helpers -------------------------------------------------

    private void sendResult(WebSocket conn, Object id, Object result) {
        try {
            conn.send(mapper.writeValueAsString(JsonRpcResponse.success(id, result)));
        } catch (Exception e) {
            log.error("Failed to send result: {}", e.getMessage());
        }
    }

    private void sendError(WebSocket conn, Object id, int code, String message) {
        try {
            conn.send(mapper.writeValueAsString(JsonRpcResponse.error(id, code, message)));
        } catch (Exception e) {
            log.error("Failed to send error: {}", e.getMessage());
        }
    }

    private void sendNotification(WebSocket conn, String method, Object params) {
        if (conn == null || !conn.isOpen()) return;
        try {
            conn.send(mapper.writeValueAsString(new JsonRpcNotification(method, params)));
        } catch (Exception e) {
            log.error("Failed to send notification: {}", e.getMessage());
        }
    }

    // ---- Inner: DebugSession -----------------------------------------------

    /**
     * Manages a single script execution (launch mode) or an attach-mode debug
     * session with its associated debugger.
     */
    public static class DebugSession {
        private final String sessionId;
        private final String code;
        private final String filePath;
        private final ScriptManager scriptManager;
        private final JythonDebugger debugger = new JythonDebugger();
        private final BreakpointManager bpManager = new BreakpointManager();
        private final DebugEventEmitter eventEmitter;
        private volatile Thread scriptThread;
        private volatile boolean attachMode = false;
        private static final Logger slog = LoggerFactory.getLogger(DebugSession.class);

        @FunctionalInterface
        interface DebugEventEmitter {
            void accept(String event, Object body);
        }

        public DebugSession(String sessionId, String code, String filePath,
                ScriptManager scriptManager, DebugEventEmitter eventEmitter) {
            this.sessionId = sessionId;
            this.code = code;
            this.filePath = filePath;
            this.scriptManager = scriptManager;
            this.eventEmitter = eventEmitter;

            debugger.setBreakpointManager(bpManager);
            debugger.setEventListener(e -> eventEmitter.accept(e.event, e.body));
        }

        /** Create an attach-mode session (no script to execute). */
        static DebugSession createAttachSession(String sessionId,
                ScriptManager scriptManager, DebugEventEmitter eventEmitter) {
            DebugSession session = new DebugSession(sessionId, "", "", scriptManager, eventEmitter);
            session.attachMode = true;
            return session;
        }

        public JythonDebugger getDebugger() {
            return debugger;
        }

        public List<BreakpointManager.Breakpoint> setBreakpoints(
                String file, List<BreakpointManager.BreakpointRequest> requests) {
            return bpManager.setBreakpoints(file, requests);
        }

        /** Execute the script in a background thread. */
        public void execute() {
            Thread t = new Thread(() -> runScript(), "ignition-debugger-" + sessionId);
            t.setDaemon(true);
            scriptThread = t;
            t.start();
        }

        /**
         * Install the debugger globally for attach mode.
         *
         * <p>Injects the trace function into the ScriptManager's globals and runs
         * a bootstrap script that calls {@code sys.settrace()} on the current
         * execution thread and {@code threading.settrace()} for future threads.
         *
         * @throws Exception if the ScriptManager rejects the bootstrap script
         */
        public void installAttach() throws Exception {
            if (!attachMode) return;
            if (scriptManager == null) {
                slog.warn("Cannot install attach-mode trace: no ScriptManager available for session {}", sessionId);
                return;
            }

            PyObject traceFunc = debugger.getTraceFunction();
            PyObject globals = scriptManager.getGlobals();

            // Inject the trace function so the bootstrap script can reference it
            globals.__setitem__(Py.newString("__ignition_debug_trace__"), traceFunc);

            String installScript =
                    "import sys\n" +
                    "sys.settrace(__ignition_debug_trace__)\n" +
                    "try:\n" +
                    "    import threading\n" +
                    "    threading.settrace(__ignition_debug_trace__)\n" +
                    "except Exception:\n" +
                    "    pass\n";

            scriptManager.runCode(installScript, globals, "<debugger-attach>");
            slog.info("Attach-mode debugger installed for session {}", sessionId);
        }

        private void runScript() {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            if (scriptManager != null) {
                runViaScriptManager(stdout, stderr);
            } else {
                runViaInterpreter(stdout, stderr);
            }
        }

        /**
         * Run the script through Ignition's ScriptManager so that the full
         * {@code system} API and other Ignition globals are available.
         *
         * <p>Strategy:
         * <ol>
         *   <li>Inject the trace function as a global variable.</li>
         *   <li>Run a tiny bootstrap script that calls {@code sys.settrace()} —
         *       this ensures the trace is active on the same thread and
         *       PySystemState that Ignition uses.</li>
         *   <li>Run the actual user code (line numbers are unaffected).</li>
         *   <li>Clean up the injected global.</li>
         * </ol>
         */
        private void runViaScriptManager(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            // Attach capture streams to the ScriptManager so that print() output
            // from user code is routed to our buffers (and then forwarded to VS Code
            // as debug.event.output events).  We remove them in the finally block.
            scriptManager.addStdOutStream(stdout);
            scriptManager.addStdErrStream(stderr);
            try {
                // Step 1 – Prime this thread's PySystemState/ThreadState by running a
                // bootstrap script through the ScriptManager.
                //
                // The 3-arg runCode(code, locals, filename) overload internally uses
                // ScriptManager.globals as the globals dict and calls setState(), which
                // calls Py.setSystemState(this.sys) on the current thread.  After this
                // call Py.getSystemState() returns the ScriptManager's PySystemState and
                // ScriptManager.globals contains a properly initialised namespace.
                //
                // Critically, the bootstrap also executes "import system" (and "import
                // fpmi" if present) so that the top-level Ignition package objects are
                // inserted as keys in ScriptManager.globals.  Without this import step
                // Jython stores the packages only in sys.modules; bare name resolution
                // (f_globals lookup) would then fail with NameError for 'system'.
                PyObject smGlobals = scriptManager.getGlobals();
                String bootstrapImports = buildBootstrapImports(smGlobals);
                // Pass smGlobals as the locals argument so that "import system" writes
                // the package object directly into smGlobals (the dict we will use as
                // both locals and globals when running the user script).
                // The 3-arg runCode(code, locals, filename) internally uses
                // ScriptManager.globals as the globals dict but writes import results
                // into the provided locals dict; by making locals == smGlobals the
                // imported names land in the right place.
                scriptManager.runCode(bootstrapImports, smGlobals, "<debugger-init>");

                // Step 2 – Install the JythonDebugger trace on the ScriptManager's
                // PySystemState (now reachable via Py.getSystemState()).
                PySystemState smSys = Py.getSystemState();
                debugger.install(smSys);

                try {
                    // Step 3 – Run the actual user script.
                    //
                    // Use getGlobals() as BOTH locals and globals so that:
                    //   • 'system', 'fpmi', etc. (populated by bootstrap) are visible
                    //   • module-level function definitions written by the script are
                    //     stored in f_globals and therefore visible to each other
                    //     (e.g. main() can call greet()).
                    scriptManager.runCode(code, smGlobals, smGlobals, filePath);
                } finally {
                    debugger.uninstall(smSys);
                    flushOutput(stdout, stderr);
                }
                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 0));
            } catch (PyException pe) {
                flushOutput(stdout, stderr);
                eventEmitter.accept("output", Map.of("category", "stderr", "output", pe.toString() + "\n"));
                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 1));
            } catch (Exception e) {
                slog.error("Error running script via ScriptManager in session {}: {}", sessionId, e.getMessage(), e);
                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 1));
            } finally {
                scriptManager.removeStdOutStream(stdout);
                scriptManager.removeStdErrStream(stderr);
            }
        }

        /**
         * Build a Python import snippet that pulls every top-level package that the
         * ScriptManager has registered into its globals dict.
         *
         * <p>Ignition modules such as {@code system}, {@code fpmi}, and {@code app} are
         * stored in {@code sys.modules} as package objects but are <em>not</em>
         * automatically present as keys in the globals dict.  A bare name reference
         * like {@code system.date.now()} resolves by walking f_locals → f_globals →
         * __builtins__; it does <em>not</em> fall through to {@code sys.modules}.
         *
         * <p>Running {@code import system} through the ScriptManager (3-arg
         * {@code runCode}, which uses ScriptManager.globals as the execution context)
         * causes Jython to insert the package object under the key {@code "system"} in
         * that globals dict.  Subsequent calls to
         * {@code runCode(userCode, globals, globals, ...)} then find {@code system}
         * via the normal f_globals lookup.
         *
         * <p>We inspect the existing globals dict for known top-level package names
         * rather than hard-coding them, so the list stays correct even when the gateway
         * registers additional scripting modules.
         */
        private String buildBootstrapImports(PyObject globals) {
            // Known top-level Ignition scripting namespaces.
            String[] candidates = { "system", "fpmi", "app", "device", "shared" };
            StringBuilder sb = new StringBuilder();
            for (String name : candidates) {
                // Import each name that is NOT already present in globals.
                // Wrapping in try/except ensures the bootstrap never fails due to a
                // missing optional module (e.g. 'fpmi' is absent in Gateway scope).
                sb.append("try:\n")
                  .append("    import ").append(name).append("\n")
                  .append("except Exception:\n")
                  .append("    pass\n");
            }
            return sb.toString();
        }

        /**
         * Fallback: run the script in a plain PythonInterpreter (no Ignition system API).
         * Used when no ScriptManager is available (e.g. in tests or Designer scope).
         */
        private void runViaInterpreter(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            try (PythonInterpreter interp = new PythonInterpreter()) {
                interp.setOut(new PrintStream(stdout));
                interp.setErr(new PrintStream(stderr));

                PySystemState sys = interp.getSystemState();
                debugger.install(sys);

                try {
                    PyCode compiled = interp.compile(code, filePath);
                    interp.exec(compiled);
                } finally {
                    debugger.uninstall(sys);
                    flushOutput(stdout, stderr);
                }

                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 0));

            } catch (PyException pe) {
                flushOutput(stdout, stderr);
                String errMsg = pe.toString();
                eventEmitter.accept("output", Map.of("category", "stderr", "output", errMsg + "\n"));
                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 1));
            } catch (Exception e) {
                slog.error("Error running script in session {}: {}", sessionId, e.getMessage(), e);
                eventEmitter.accept("terminated", null);
                eventEmitter.accept("exited", Map.of("exitCode", 1));
            }
        }

        private void flushOutput(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
            String out = stdout.toString();
            String err = stderr.toString();
            if (!out.isEmpty()) {
                eventEmitter.accept("output", Map.of("category", "stdout", "output", out));
            }
            if (!err.isEmpty()) {
                eventEmitter.accept("output", Map.of("category", "stderr", "output", err));
            }
        }

        /** Stop the running script or detach from attach mode (best-effort). */
        public void abort() {
            if (attachMode) {
                uninstallAttach();
                debugger.deactivate();
            } else {
                debugger.uninstall(Py.getSystemState());
                Thread t = scriptThread;
                if (t != null && t.isAlive()) {
                    t.interrupt();
                }
            }
        }

        /**
         * Remove the globally installed trace function (attach mode cleanup).
         */
        private void uninstallAttach() {
            if (scriptManager == null) return;
            try {
                PyObject globals = scriptManager.getGlobals();
                String cleanup =
                        "import sys\n" +
                        "sys.settrace(None)\n" +
                        "try:\n" +
                        "    import threading\n" +
                        "    threading.settrace(None)\n" +
                        "except Exception:\n" +
                        "    pass\n";
                scriptManager.runCode(cleanup, globals, "<debugger-detach>");
                try {
                    globals.__delitem__(Py.newString("__ignition_debug_trace__"));
                } catch (Exception ignored) { }
            } catch (Exception e) {
                slog.warn("Error cleaning up attach session {}: {}", sessionId, e.getMessage());
            }
        }
    }
}
