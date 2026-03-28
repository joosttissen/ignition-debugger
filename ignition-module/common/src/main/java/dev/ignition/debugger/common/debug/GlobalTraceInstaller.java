package dev.ignition.debugger.common.debug;

import org.python.core.ThreadState;
import org.python.core.TraceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Installs a {@link TraceFunction} on <em>all</em> Jython {@link ThreadState}
 * objects – including those created <strong>after</strong> the installer starts.
 *
 * <h2>Strategy</h2>
 * <p>Jython stores every active {@code ThreadState} in the package-private
 * static map {@code ThreadStateMapping.globalThreadStates}.  When a Java thread
 * enters Jython for the first time (e.g. a Jetty servlet thread handling a
 * WebDev request), a new {@code ThreadState} is created and added to this map
 * via {@link java.util.Map#put}.
 *
 * <p>We replace the map with a thin wrapper ({@link TraceInstallingMap}) that
 * overrides {@code put()} to set {@code ThreadState.tracefunc} on every newly
 * created {@code ThreadState}.  This eliminates the race condition inherent in
 * polling: the trace function is installed <strong>before</strong>
 * {@code getThreadState()} returns the new {@code ThreadState} to the caller.
 *
 * <p>Using {@code sun.misc.Unsafe} is necessary because
 * {@code globalThreadStates} is declared {@code private static final} and
 * {@code Field.set()} on final static fields throws
 * {@link IllegalAccessException} on Java 12+.  The Ignition JVM already ships
 * with {@code --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED}.
 */
public class GlobalTraceInstaller {

    private static final Logger log = LoggerFactory.getLogger(GlobalTraceInstaller.class);

    private TraceInstallingMap installedMap;

    // ---- Public API --------------------------------------------------------

    /**
     * Replace the global ThreadState map with an intercepting version that
     * auto-installs {@code tf} on every new (and existing) {@code ThreadState}.
     */
    @SuppressWarnings("unchecked")
    public void start(TraceFunction tf) {
        try {
            Class<?> tsmClass = Class.forName("org.python.core.ThreadStateMapping");
            Field mapField = tsmClass.getDeclaredField("globalThreadStates");
            mapField.setAccessible(true);

            Map<Thread, ThreadState> original =
                    (Map<Thread, ThreadState>) mapField.get(null);

            // Build the replacement map (copies existing entries)
            installedMap = new TraceInstallingMap(original, tf);

            // Install trace on existing ThreadStates that were copied in
            int installed = 0;
            for (ThreadState ts : installedMap.values()) {
                if (!(ts.tracefunc instanceof TraceFunctionAdapter)) {
                    ts.tracefunc = tf;
                    installed++;
                }
            }

            // Swap the field atomically using Unsafe
            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.staticFieldOffset(mapField);
            unsafe.putObject(tsmClass, offset, installedMap);

            log.info("GlobalTraceInstaller started – intercepting map installed "
                    + "({} existing ThreadStates, trace set on {})", installedMap.size(), installed);

        } catch (Exception e) {
            log.error("GlobalTraceInstaller failed to start: {}", e.getMessage(), e);
        }
    }

    /**
     * Disable trace installation and clean up.
     *
     * <p>The intercepting map remains in place (it is a thread-safe
     * {@link ConcurrentHashMap} – functionally equivalent to the original).
     * Only the trace-installation behaviour is disabled.
     */
    public void stop() {
        if (installedMap != null) {
            installedMap.clearTraceFunction();

            for (ThreadState ts : installedMap.values()) {
                if (ts.tracefunc instanceof TraceFunctionAdapter) {
                    ts.tracefunc = null;
                }
            }
            installedMap = null;
        }
        log.info("GlobalTraceInstaller stopped");
    }

    // ---- Unsafe access -----------------------------------------------------

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    // ---- Intercepting map --------------------------------------------------

    /**
     * A {@link ConcurrentHashMap} that intercepts {@code put()} to install a
     * {@link TraceFunction} on every newly added {@link ThreadState}.
     */
    @SuppressWarnings("serial")
    static class TraceInstallingMap extends ConcurrentHashMap<Thread, ThreadState> {

        private volatile TraceFunction traceFunction;

        TraceInstallingMap(Map<Thread, ThreadState> source, TraceFunction tf) {
            super(source);
            this.traceFunction = tf;
        }

        @Override
        public ThreadState put(Thread key, ThreadState value) {
            TraceFunction tf = this.traceFunction;
            if (tf != null && value != null) {
                value.tracefunc = tf;
            }
            log.info("TraceInstallingMap.put() called for thread '{}' (traceFunc={})",
                    key.getName(), tf != null ? "set" : "null");
            return super.put(key, value);
        }

        void clearTraceFunction() {
            this.traceFunction = null;
        }
    }
}
