package dev.ignition.debugger.common.debug;

import org.python.core.Py;
import org.python.core.ThreadState;
import org.python.core.TraceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.InputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Installs a {@link TraceFunction} on <em>all</em> Jython {@link ThreadState}
 * objects – including those created <strong>after</strong> the installer starts.
 *
 * <h2>Strategy</h2>
 * <p>Injects a {@code TracingThreadStateMapping} subclass into the same
 * classloader that loaded Jython's {@code ThreadStateMapping}, then replaces
 * the singleton {@code Py.threadStateMapping}.  The subclass overrides
 * {@code getThreadState()} to install the trace function on every ThreadState
 * as it is obtained — guaranteeing visibility before
 * {@code PyTableCode.call()} reads {@code ts.tracefunc}.</p>
 */
public class GlobalTraceInstaller {

    private static final Logger log = LoggerFactory.getLogger(GlobalTraceInstaller.class);

    /** The injected subclass instance (set by reflection). */
    private Object tracingMapping;
    /** The original ThreadStateMapping, saved for restoration on stop(). */
    private Object originalMapping;

    // ---- Public API --------------------------------------------------------

    public void start(TraceFunction tf) {
        try {
            // 1. Load TracingThreadStateMapping bytecode from our module classloader
            String classResourcePath = "org/python/core/TracingThreadStateMapping.class";
            byte[] classBytes;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(classResourcePath)) {
                if (is == null) {
                    throw new RuntimeException("Cannot find " + classResourcePath + " in module classloader");
                }
                classBytes = is.readAllBytes();
            }

            // 2. Inject the class into the classloader that loaded ThreadStateMapping
            Class<?> tsmClass = Class.forName("org.python.core.ThreadStateMapping");
            ClassLoader targetCL = tsmClass.getClassLoader();

            // Use ClassLoader.defineClass() via reflection to inject our class
            // into the same classloader that owns org.python.core
            Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class,
                    java.security.ProtectionDomain.class);
            defineClassMethod.setAccessible(true);
            Class<?> tracingClass = (Class<?>) defineClassMethod.invoke(
                    targetCL,
                    "org.python.core.TracingThreadStateMapping",
                    classBytes, 0, classBytes.length,
                    tsmClass.getProtectionDomain());

            // 3. Instantiate and configure
            tracingMapping = tracingClass.getDeclaredConstructor().newInstance();
            Method setTF = tracingClass.getMethod("setTraceFunction", TraceFunction.class);
            setTF.invoke(tracingMapping, tf);

            // 4. Replace Py.threadStateMapping
            Field tsmField = Py.class.getDeclaredField("threadStateMapping");
            tsmField.setAccessible(true);

            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.staticFieldOffset(tsmField);
            originalMapping = unsafe.getObject(Py.class, offset);
            unsafe.putObject(Py.class, offset, tracingMapping);

            // 4b. Force JIT to deoptimize Py.getThreadState methods.
            // The JIT may have constant-folded the static final field read for
            // Py.threadStateMapping and cached the old instance.  We load a
            // temporary Java agent that calls Instrumentation.retransformClasses
            // to force the JIT to discard compiled code for Py.
            deoptimizePyGetThreadState();

            // 5. Also install trace on existing ThreadStates
            int installed = tagExistingThreadStates(tf);

            log.info("GlobalTraceInstaller started – TracingThreadStateMapping injected "
                    + "(tagged {} existing ThreadStates)", installed);

        } catch (Exception e) {
            log.error("GlobalTraceInstaller failed to start: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        // Disable tracing in the mapping
        if (tracingMapping != null) {
            try {
                Method setTF = tracingMapping.getClass().getMethod("setTraceFunction", TraceFunction.class);
                setTF.invoke(tracingMapping, (TraceFunction) null);
            } catch (Exception e) {
                log.debug("Error clearing trace function: {}", e.getMessage());
            }
        }

        // Restore original ThreadStateMapping
        if (originalMapping != null) {
            try {
                sun.misc.Unsafe unsafe = getUnsafe();
                Field tsmField = Py.class.getDeclaredField("threadStateMapping");
                tsmField.setAccessible(true);
                long offset = unsafe.staticFieldOffset(tsmField);
                unsafe.putObject(Py.class, offset, originalMapping);
            } catch (Exception e) {
                log.debug("Error restoring original mapping: {}", e.getMessage());
            }
            originalMapping = null;
        }

        // Clear trace from existing ThreadStates
        clearExistingThreadStates();

        tracingMapping = null;
        log.info("GlobalTraceInstaller stopped");
    }

    // ---- Helpers ------------------------------------------------------------

    private int tagExistingThreadStates(TraceFunction tf) {
        int installed = 0;
        try {
            Class<?> tsmClass = Class.forName("org.python.core.ThreadStateMapping");
            Field mapField = tsmClass.getDeclaredField("globalThreadStates");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Thread, ThreadState> map =
                    (java.util.Map<Thread, ThreadState>) mapField.get(null);
            for (ThreadState ts : map.values()) {
                if (ts != null && !(ts.tracefunc instanceof TraceFunctionAdapter)) {
                    ts.tracefunc = tf;
                    installed++;
                }
            }
        } catch (Exception e) {
            log.debug("tagExistingThreadStates error: {}", e.getMessage());
        }
        return installed;
    }

    private void clearExistingThreadStates() {
        try {
            Class<?> tsmClass = Class.forName("org.python.core.ThreadStateMapping");
            Field mapField = tsmClass.getDeclaredField("globalThreadStates");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Thread, ThreadState> map =
                    (java.util.Map<Thread, ThreadState>) mapField.get(null);
            for (ThreadState ts : map.values()) {
                if (ts != null && ts.tracefunc instanceof TraceFunctionAdapter) {
                    ts.tracefunc = null;
                }
            }
        } catch (Exception e) {
            log.debug("clearExistingThreadStates error: {}", e.getMessage());
        }
    }

    // ---- JIT deoptimization ------------------------------------------------

    /**
     * Forces the JIT compiler to discard compiled code for {@code Py} class
     * methods so that the updated {@code threadStateMapping} field value is
     * read from memory rather than a constant-folded register.
     *
     * <p>Creates a temporary agent JAR from the {@link DeoptAgent} class,
     * loads it via the {@code jvmtiAgentLoad} diagnostic command.  The agent's
     * {@code agentmain} method calls {@code Instrumentation.retransformClasses}
     * on {@code Py.class}, which forces deoptimization.</p>
     */
    private void deoptimizePyGetThreadState() {
        Path agentJar = null;
        try {
            agentJar = buildAgentJar();
            loadAgentViaJmx(agentJar, "org.python.core.Py");
            log.info("JIT deoptimization of Py class completed");
        } catch (Exception e) {
            log.warn("JIT deoptimization failed: {}. "
                    + "Attach-mode breakpoints may not work if Py.getThreadState "
                    + "has been JIT-compiled. Add JVM flag: "
                    + "-XX:CompileCommand=exclude,org/python/core/Py.getThreadState",
                    e.getMessage());
        } finally {
            if (agentJar != null) {
                try { Files.deleteIfExists(agentJar); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Builds a minimal agent JAR containing the DeoptAgent class with the
     * required manifest attributes.
     */
    private Path buildAgentJar() throws IOException {
        Path jar = Files.createTempFile("deopt-agent-", ".jar");
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Agent-Class", DeoptAgent.class.getName());
        attrs.putValue("Can-Retransform-Classes", "true");
        attrs.putValue("Can-Redefine-Classes", "true");

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // Write the DeoptAgent class file
            String classPath = DeoptAgent.class.getName().replace('.', '/') + ".class";
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(classPath)) {
                if (is == null) {
                    throw new IOException("Cannot find " + classPath + " in classloader");
                }
                jos.putNextEntry(new JarEntry(classPath));
                is.transferTo(jos);
                jos.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Loads the agent JAR into the current JVM using the
     * {@code jvmtiAgentLoad} diagnostic command via JMX.
     *
     * @param agentJar path to the agent JAR file
     * @param agentArgs arguments passed to {@code agentmain}
     */
    private void loadAgentViaJmx(Path agentJar, String agentArgs) throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName diagCmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
        // jvmtiAgentLoad takes: library_path [agent_option]
        String loadArg = agentJar.toAbsolutePath().toString();
        if (agentArgs != null && !agentArgs.isEmpty()) {
            loadArg += "=" + agentArgs;
        }
        String[] sig = { String[].class.getName() };
        Object[] params = { new String[] { loadArg } };
        Object result = mbs.invoke(diagCmd, "jvmtiAgentLoad", params, sig);
        log.info("jvmtiAgentLoad result: {}", result);
    }

    // ---- Unsafe access -----------------------------------------------------

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
