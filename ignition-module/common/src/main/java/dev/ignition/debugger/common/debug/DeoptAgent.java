package dev.ignition.debugger.common.debug;

import java.lang.instrument.Instrumentation;

/**
 * Minimal Java agent that can be loaded at runtime via the JMX
 * {@code jvmtiAgentLoad} diagnostic command.
 *
 * <p>When loaded, it immediately retransforms the class specified in the
 * {@code agentArgs} parameter (or {@code org.python.core.Py} by default).
 * Retransforming a class forces the JIT to discard compiled code for
 * that class's methods, ensuring that any {@code static final} field
 * changes made via {@link sun.misc.Unsafe} become visible.</p>
 *
 * <p>The {@code MANIFEST.MF} in the agent JAR must contain:
 * <pre>
 * Agent-Class: dev.ignition.debugger.common.debug.DeoptAgent
 * Can-Retransform-Classes: true
 * </pre>
 */
public class DeoptAgent {

    /**
     * Called when the agent is loaded at runtime (via Attach API or jvmtiAgentLoad).
     * Retransforms the target class to force JIT deoptimization.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        String className = (agentArgs != null && !agentArgs.isEmpty())
                ? agentArgs : "org.python.core.Py";
        try {
            Class<?> target = Class.forName(className);
            inst.retransformClasses(target);
            System.out.println("[DeoptAgent] Successfully retransformed " + className);
        } catch (Exception e) {
            System.err.println("[DeoptAgent] Failed to retransform " + className + ": " + e);
        }
    }

    /**
     * Called when the agent is loaded at JVM start (via -javaagent).
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        agentmain(agentArgs, inst);
    }
}
