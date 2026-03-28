package dev.ignition.debugger.common.debug;

import org.python.core.*;

/**
 * A Java-level {@link TraceFunction} that delegates trace events to a
 * {@link JythonDebugger} instance.
 *
 * <p>Unlike the Python-level trace function installed via {@code sys.settrace()},
 * this adapter can be set directly on a {@link ThreadState#tracefunc} field from
 * Java, which allows installing traces on threads we don't control (e.g. Jetty
 * servlet threads used by Ignition's WebDev module).
 */
public class TraceFunctionAdapter extends TraceFunction {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TraceFunctionAdapter.class);

    private final JythonDebugger debugger;
    private volatile boolean loggedFirstCall = false;

    public TraceFunctionAdapter(JythonDebugger debugger) {
        this.debugger = debugger;
    }

    @Override
    public TraceFunction traceCall(PyFrame frame) {
        try {
            log.info("traceCall: file={}, func={}, line={}, thread={}",
                    frame.f_code.co_filename, frame.f_code.co_name,
                    frame.f_lineno, Thread.currentThread().getName());
        } catch (Exception e) {
            log.info("traceCall: (error reading frame)");
        }
        logFirstCall(frame, "call");
        debugger.handleTraceEventDirect(frame, "call");
        return debugger.isActive() ? this : null;
    }

    @Override
    public TraceFunction traceReturn(PyFrame frame, PyObject retVal) {
        debugger.handleTraceEventDirect(frame, "return");
        return debugger.isActive() ? this : null;
    }

    @Override
    public TraceFunction traceLine(PyFrame frame, int line) {
        try {
            log.debug("traceLine: file={}, line={}", frame.f_code.co_filename, line);
        } catch (Exception ignore) {}
        debugger.handleTraceEventDirect(frame, "line");
        return debugger.isActive() ? this : null;
    }

    @Override
    public TraceFunction traceException(PyFrame frame, PyException exc) {
        debugger.handleTraceEventDirect(frame, "exception");
        return debugger.isActive() ? this : null;
    }

    private void logFirstCall(PyFrame frame, String event) {
        if (!loggedFirstCall) {
            loggedFirstCall = true;
            try {
                String filename = frame.f_code.co_filename;
                String funcname = frame.f_code.co_name;
                int lineno = frame.f_lineno;
                log.info("TraceFunctionAdapter FIRST TRACE: event={}, file={}, func={}, line={}, thread={}",
                        event, filename, funcname, lineno, Thread.currentThread().getName());
            } catch (Exception e) {
                log.info("TraceFunctionAdapter first call (error reading frame): {}", e.getMessage());
            }
        }
    }
}
