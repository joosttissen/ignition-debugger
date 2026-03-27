package dev.ignition.debugger.common.debug;

import org.python.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Implements step-level Jython debugging via {@code sys.settrace}.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Before running the user script, {@link #install(PySystemState)} registers a trace
 *       function with the Jython interpreter.</li>
 *   <li>The trace function is called by Jython for every <em>call</em>,
 *       <em>line</em>, and <em>return</em> event.</li>
 *   <li>When the current line matches a breakpoint (or a step condition),
 *       the script thread is paused by awaiting a {@link CountDownLatch}.</li>
 *   <li>Debug commands from VS Code ({@code continue}, {@code stepOver}, …)
 *       set the step mode and count down the latch to resume execution.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * The trace function runs in the Jython script thread.  Command methods
 * ({@link #resume()}, {@link #stepOver()}, etc.) are called from the
 * WebSocket handler thread.
 */
public class JythonDebugger {

    private static final Logger log = LoggerFactory.getLogger(JythonDebugger.class);

    // ---- Step modes --------------------------------------------------------

    public enum StepMode {
        RUN,        // execute until next breakpoint
        STEP_IN,    // stop at next line event
        STEP_OVER,  // stop when stack depth returns to current level
        STEP_OUT    // stop when stack depth is less than current level
    }

    // ---- Internal state (accessed from both threads) ----------------------

    private volatile StepMode stepMode = StepMode.RUN;
    private volatile int stepBaseDepth = 0;
    private volatile CountDownLatch pauseLatch = null;
    private volatile boolean active = false;

    /** Current call-stack depth (incremented on 'call', decremented on 'return'). */
    private int stackDepth = 0;

    /** Captured stack frames at the point of the last pause. */
    private final List<FrameInfo> capturedFrames = new ArrayList<>();

    /** Captured variable scopes (variablesReference -> variable map). */
    private final Map<Integer, Map<String, PyObject>> variableScopes = new ConcurrentHashMap<>();
    private final AtomicInteger varRefCounter = new AtomicInteger(1000);

    /** Listener that receives debug events to send to VS Code. */
    private Consumer<DebugEvent> eventListener;

    // ---- Public API --------------------------------------------------------

    /** Set the listener that will receive debug events. */
    public void setEventListener(Consumer<DebugEvent> listener) {
        this.eventListener = listener;
    }

    /**
     * Install the trace function into the current Jython interpreter.
     * Must be called in the Jython thread before executing the user script.
     */
    public void install(PySystemState sys) {
        active = true;
        stackDepth = 0;
        capturedFrames.clear();
        variableScopes.clear();

        PyObject traceFunc = buildTraceFunction();
        sys.settrace(traceFunc);
        log.debug("JythonDebugger: trace function installed");
    }

    /** Remove the trace function and unblock the script thread if paused. */
    public void uninstall(PySystemState sys) {
        active = false;
        sys.settrace(null);
        resume(); // unblock if paused
        log.debug("JythonDebugger: trace function removed");
    }

    /**
     * Build and return the trace function for external installation (e.g. attach mode).
     * Also activates the debugger so it processes trace events.
     *
     * @return a {@link PyObject} suitable for passing to {@code sys.settrace()}
     */
    public PyObject getTraceFunction() {
        active = true;
        stackDepth = 0;
        capturedFrames.clear();
        variableScopes.clear();
        return buildTraceFunction();
    }

    /** Deactivate the debugger and unblock the script thread if paused. */
    public void deactivate() {
        active = false;
        resume();
        log.debug("JythonDebugger: deactivated");
    }

    /** Resume execution (continue). */
    public void resume() {
        stepMode = StepMode.RUN;
        releasePause();
    }

    /** Step over the current line (next). */
    public void stepOver() {
        stepMode = StepMode.STEP_OVER;
        stepBaseDepth = stackDepth;
        releasePause();
    }

    /** Step into the next call (stepIn). */
    public void stepIn() {
        stepMode = StepMode.STEP_IN;
        releasePause();
    }

    /** Step out of the current function (stepOut). */
    public void stepOut() {
        stepMode = StepMode.STEP_OUT;
        stepBaseDepth = stackDepth;
        releasePause();
    }

    /**
     * Returns a snapshot of the call stack at the last pause point.
     * Each entry corresponds to one DAP stack frame.
     */
    public List<FrameInfo> getCapturedFrames() {
        return Collections.unmodifiableList(capturedFrames);
    }

    /**
     * Returns the variables associated with a variablesReference handle.
     * Returns an empty map for unknown references.
     */
    public Map<String, Object> getVariables(int variablesReference) {
        Map<String, PyObject> scope = variableScopes.get(variablesReference);
        if (scope == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, PyObject> entry : scope.entrySet()) {
            result.put(entry.getKey(), describeVariable(entry.getValue()));
        }
        return result;
    }

    /**
     * Evaluates a Python expression in the context of the given frame index.
     * The frame index maps to {@link #getCapturedFrames()}.
     *
     * @return a string representation of the result, or an error message
     */
    public String evaluate(String expression, int frameIndex) {
        if (capturedFrames.isEmpty() || frameIndex >= capturedFrames.size()) {
            return "<no frame>";
        }
        FrameInfo fi = capturedFrames.get(frameIndex);
        PyObject frame = fi.pyFrame;
        if (frame == null) {
            return "<no frame>";
        }
        try {
            PyObject locals = frame.__getattr__("f_locals");
            PyObject globals = frame.__getattr__("f_globals");
            return org.python.core.__builtin__.eval(
                    Py.newString(expression), globals, locals).__repr__().toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---- Trace function ----------------------------------------------------

    private PyObject buildTraceFunction() {
        return new PyObject() {
            @Override
            public PyObject __call__(PyObject[] args, String[] keywords) {
                if (!active || args.length < 2) {
                    return Py.None;
                }
                PyObject frame = args[0];
                String event = args[1].toString();

                handleTraceEvent(frame, event);
                return this; // return self to keep tracing on inner calls
            }
        };
    }

    private void handleTraceEvent(PyObject frame, String event) {
        try {
            switch (event) {
                case "call":
                    stackDepth++;
                    break;
                case "return":
                    if (stackDepth > 0) stackDepth--;
                    checkStepOut(frame);
                    break;
                case "line":
                    checkPauseOnLine(frame);
                    break;
                case "exception":
                    checkPauseOnException(frame);
                    break;
                default:
                    break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error in trace function: {}", e.getMessage(), e);
        }
    }

    private void checkPauseOnLine(PyObject frame) throws InterruptedException {
        if (!active) return;

        boolean shouldPause = false;
        String pauseReason = "step";

        switch (stepMode) {
            case STEP_IN:
                shouldPause = true;
                break;
            case STEP_OVER:
                shouldPause = (stackDepth <= stepBaseDepth);
                break;
            case RUN:
                // Check breakpoints
                String filename = safeGetString(frame, "f_code", "co_filename");
                int lineNo = safeGetInt(frame, "f_lineno");
                if (isBreakpoint(filename, lineNo)) {
                    shouldPause = true;
                    pauseReason = "breakpoint";
                }
                break;
            default:
                break;
        }

        if (shouldPause) {
            captureFrames(frame);
            stepMode = StepMode.RUN; // reset – next continue will run freely
            pause(pauseReason);
        }
    }

    private void checkStepOut(PyObject frame) throws InterruptedException {
        if (!active || stepMode != StepMode.STEP_OUT) return;
        if (stackDepth < stepBaseDepth) {
            captureFrames(frame);
            stepMode = StepMode.RUN;
            pause("step");
        }
    }

    private void checkPauseOnException(PyObject frame) throws InterruptedException {
        // For now we do not stop on exceptions; this can be added later.
    }

    // ---- Breakpoint check (delegated to BreakpointManager) -----------------

    private BreakpointManager breakpointManager;

    /** Inject the BreakpointManager to check against. */
    public void setBreakpointManager(BreakpointManager bpManager) {
        this.breakpointManager = bpManager;
    }

    private boolean isBreakpoint(String filename, int line) {
        return breakpointManager != null && breakpointManager.isBreakpoint(filename, line);
    }

    // ---- Pause / resume ----------------------------------------------------

    private void pause(String reason) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        pauseLatch = latch;

        emitEvent("stopped", Map.of(
                "reason", reason,
                "threadId", 1,
                "allThreadsStopped", true
        ));

        latch.await(); // block the script thread until resumed
    }

    private void releasePause() {
        CountDownLatch latch = pauseLatch;
        if (latch != null) {
            pauseLatch = null;
            latch.countDown();
        }
    }

    // ---- Frame capture -----------------------------------------------------

    private void captureFrames(PyObject topFrame) {
        capturedFrames.clear();
        variableScopes.clear();
        int frameId = 0;
        PyObject current = topFrame;
        while (current != null && current != Py.None) {
            try {
                String name = safeGetString(current, "f_code", "co_name");
                String file = safeGetString(current, "f_code", "co_filename");
                int line = safeGetInt(current, "f_lineno");

                int localsRef = registerScope(current, "f_locals");
                int globalsRef = registerScope(current, "f_globals");

                capturedFrames.add(new FrameInfo(frameId++, name, file, line, localsRef, globalsRef, current));

                // Walk to the outer frame
                PyObject back = current.__getattr__("f_back");
                current = (back == Py.None) ? null : back;
            } catch (Exception e) {
                break;
            }
        }
    }

    private int registerScope(PyObject frame, String attr) {
        try {
            PyObject dict = frame.__getattr__(attr);
            Map<String, PyObject> scope = new LinkedHashMap<>();
            // f_locals / f_globals may be PyStringMap (not PyDictionary).
            // Use the generic PyObject.keys() / __getitem__ interface instead.
            PyObject keys;
            try {
                keys = dict.invoke("keys");
            } catch (Exception e) {
                keys = null;
            }
            if (keys != null) {
                PyObject iter = keys.__iter__();
                PyObject key;
                while ((key = iter.__iternext__()) != null) {
                    try {
                        String k = key.toString();
                        PyObject val = dict.__getitem__(key);
                        scope.put(k, val);
                    } catch (Exception ignored) {}
                }
            }
            int ref = varRefCounter.getAndIncrement();
            variableScopes.put(ref, scope);
            return ref;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- Variable description ----------------------------------------------

    private VariableInfo describeVariable(PyObject obj) {
        String value;
        String type;
        int varRef = 0;

        try {
            type = obj.getType().getName();
            value = obj.__repr__().toString();

            // For dicts and lists, expose children via a nested variablesReference
            if (obj instanceof PyDictionary || obj instanceof PyList || obj instanceof PyTuple) {
                int ref = varRefCounter.getAndIncrement();
                Map<String, PyObject> children = new LinkedHashMap<>();
                if (obj instanceof PyDictionary) {
                    for (Object key : ((PyDictionary) obj).keys()) {
                        String k = key.toString();
                        children.put(k, ((PyDictionary) obj).__getitem__(Py.newString(k)));
                    }
                } else {
                    PySequenceList seq = (PySequenceList) obj;
                    for (int i = 0; i < seq.size(); i++) {
                        children.put("[" + i + "]", seq.__getitem__(i));
                    }
                }
                variableScopes.put(ref, children);
                varRef = ref;
            }
        } catch (Exception e) {
            value = "<error>";
            type = "unknown";
        }

        return new VariableInfo(value, type, varRef);
    }

    // ---- Utility helpers ---------------------------------------------------

    private static String safeGetString(PyObject obj, String... attrs) {
        try {
            PyObject current = obj;
            for (String attr : attrs) {
                current = current.__getattr__(attr);
            }
            return current.toString();
        } catch (Exception e) {
            return "<unknown>";
        }
    }

    private static int safeGetInt(PyObject obj, String attr) {
        try {
            return ((PyInteger) obj.__getattr__(attr)).getValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private void emitEvent(String event, Object body) {
        Consumer<DebugEvent> listener = eventListener;
        if (listener != null) {
            listener.accept(new DebugEvent(event, body));
        }
    }

    // ---- Inner types -------------------------------------------------------

    /** A captured stack frame. */
    public static class FrameInfo {
        public final int id;
        public final String name;
        public final String filePath;
        public final int line;
        public final int localsRef;
        public final int globalsRef;
        /** Keep a reference to the Jython frame for evaluate(). */
        public final PyObject pyFrame;

        public FrameInfo(int id, String name, String filePath, int line,
                int localsRef, int globalsRef, PyObject pyFrame) {
            this.id = id;
            this.name = name;
            this.filePath = filePath;
            this.line = line;
            this.localsRef = localsRef;
            this.globalsRef = globalsRef;
            this.pyFrame = pyFrame;
        }
    }

    /** Describes a single variable value. */
    public static class VariableInfo {
        public final String value;
        public final String type;
        public final int variablesReference;

        public VariableInfo(String value, String type, int variablesReference) {
            this.value = value;
            this.type = type;
            this.variablesReference = variablesReference;
        }
    }

    /** A debug event to send to the VS Code client. */
    public static class DebugEvent {
        public final String event;
        public final Object body;

        public DebugEvent(String event, Object body) {
            this.event = event;
            this.body = body;
        }
    }
}
