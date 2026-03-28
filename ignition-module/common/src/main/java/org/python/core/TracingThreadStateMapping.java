package org.python.core;

/**
 * A {@link ThreadStateMapping} subclass that automatically installs a
 * {@link TraceFunction} on every {@link ThreadState} returned by
 * {@link #getThreadState(PySystemState)}.
 *
 * <p>By replacing the singleton in {@code Py.threadStateMapping} with an
 * instance of this class, we intercept <em>all</em> ThreadState lookups
 * (including creation of new ThreadStates for threads that have never run
 * Jython code before).  This guarantees the trace is visible to
 * {@code PyTableCode.call()} which reads {@code ts.tracefunc} immediately
 * after obtaining the ThreadState.
 */
public class TracingThreadStateMapping extends ThreadStateMapping {

    private volatile TraceFunction traceFunction;

    public TracingThreadStateMapping() {
        super();
    }

    /**
     * Set the trace function to install on all ThreadStates.
     * Pass {@code null} to stop tracing.
     */
    public void setTraceFunction(TraceFunction tf) {
        this.traceFunction = tf;
    }

    @Override
    public ThreadState getThreadState(PySystemState newSystemState) {
        ThreadState ts = super.getThreadState(newSystemState);
        TraceFunction tf = this.traceFunction;
        if (tf != null && ts.tracefunc != tf) {
            ts.tracefunc = tf;
        }
        return ts;
    }
}
