package dev.ignition.debugger.common.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * WebSocket server embedded in an Ignition module scope (Designer or Gateway).
 *
 * <p>Listens on {@code 127.0.0.1:{port}} and handles JSON-RPC 2.0 requests
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

    // ---- Construction / lifecycle -----------------------------------------

    /**
     * @param port   TCP port to listen on (0 = auto-assign)
     * @param secret shared secret for authentication
     */
    public DebugWebSocketServer(int port, String secret) {
        super(new InetSocketAddress("127.0.0.1", port));
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

        String sessionId = "session-" + sessionCounter.incrementAndGet();
        DebugSession session = new DebugSession(sessionId, code, filePath,
                (event, body) -> sendNotification(conn, "debug.event." + event, body));
        sessions.put(sessionId, session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("sessionId", sessionId);
        sendResult(conn, id, result);
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
     * Manages a single script execution with its associated debugger.
     */
    public static class DebugSession {
        private final String sessionId;
        private final String code;
        private final String filePath;
        private final JythonDebugger debugger = new JythonDebugger();
        private final BreakpointManager bpManager = new BreakpointManager();
        private final EventEmitter eventEmitter;
        private volatile Thread scriptThread;
        private static final Logger slog = LoggerFactory.getLogger(DebugSession.class);

        @FunctionalInterface
        interface EventEmitter {
            void accept(String event, Object body);
        }

        public DebugSession(String sessionId, String code, String filePath, EventEmitter eventEmitter) {
            this.sessionId = sessionId;
            this.code = code;
            this.filePath = filePath;
            this.eventEmitter = eventEmitter;

            debugger.setBreakpointManager(bpManager);
            debugger.setEventListener(e -> eventEmitter.accept(e.event, e.body));
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

        private void runScript() {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            try (PythonInterpreter interp = new PythonInterpreter()) {
                interp.setOut(new PrintStream(stdout));
                interp.setErr(new PrintStream(stderr));

                PySystemState sys = interp.getSystemState();

                // Install the trace function
                debugger.install(sys);

                try {
                    interp.exec(code);
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

        /** Stop the running script (best-effort). */
        public void abort() {
            debugger.uninstall(Py.getSystemState());
            Thread t = scriptThread;
            if (t != null && t.isAlive()) {
                t.interrupt();
            }
        }
    }
}
