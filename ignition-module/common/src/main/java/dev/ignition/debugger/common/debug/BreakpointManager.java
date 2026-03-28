package dev.ignition.debugger.common.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stores and matches breakpoints set by the VS Code debug adapter.
 */
public class BreakpointManager {

    private final AtomicInteger idCounter = new AtomicInteger(0);

    /** filePath -> list of breakpoints */
    private final Map<String, List<Breakpoint>> byFile = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Replace all breakpoints for a given file path.
     *
     * @param filePath  normalised file path as sent by VS Code
     * @param requests  new set of breakpoints (line + optional condition)
     * @return the verified breakpoints to return to VS Code
     */
    public List<Breakpoint> setBreakpoints(String filePath, List<BreakpointRequest> requests) {
        List<Breakpoint> result = new ArrayList<>();
        for (BreakpointRequest req : requests) {
            Breakpoint bp = new Breakpoint(idCounter.incrementAndGet(), req.line, req.condition);
            bp.verified = true;
            result.add(bp);
        }
        byFile.put(filePath, result);
        return result;
    }

    /**
     * Returns {@code true} if there is a breakpoint at the given file and line.
     *
     * <p>Uses suffix matching: the Jython {@code co_filename} inside the
     * container may differ from the host path sent by VS Code, but both
     * typically share a common tail (e.g. {@code resources/test/doGet.py}).
     *
     * <p>Also handles Ignition's internal {@code <<project/resource:func>>}
     * filename format used by WebDev's {@code ScriptManager.compileFunction()}.
     */
    public boolean isBreakpoint(String filePath, int line) {
        // Fast path: exact match
        List<Breakpoint> bps = byFile.get(filePath);
        if (bps != null) {
            for (Breakpoint bp : bps) {
                if (bp.line == line) return true;
            }
        }
        // Slow path: suffix match or Ignition-internal format match
        for (Map.Entry<String, List<Breakpoint>> entry : byFile.entrySet()) {
            if (pathsSuffixMatch(entry.getKey(), filePath)
                    || ignitionInternalMatch(entry.getKey(), filePath)) {
                for (Breakpoint bp : entry.getValue()) {
                    if (bp.line == line) return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if one path is a suffix of the other,
     * respecting path separator boundaries.
     */
    private static boolean pathsSuffixMatch(String a, String b) {
        if (a.equals(b)) return true;
        String na = a.replace('\\', '/');
        String nb = b.replace('\\', '/');
        if (na.equals(nb)) return true;
        String shorter = na.length() <= nb.length() ? na : nb;
        String longer  = na.length() >  nb.length() ? na : nb;
        return longer.endsWith("/" + shorter) || longer.endsWith(shorter);
    }

    /**
     * Matches Ignition's internal {@code <<project/resource:func>>} filename
     * format against a VS Code breakpoint path.
     *
     * <p>For example, Jython may report a WebDev script's co_filename as
     * {@code <<test-scripting/test:doGet>>}, while VS Code sets breakpoints
     * using the host path like {@code .../test-scripting/.../resources/test/doGet.py}.
     *
     * <p>This method extracts the project name, resource path, and function name
     * from the internal format and checks if the breakpoint path contains those
     * components.
     */
    private static boolean ignitionInternalMatch(String bpPath, String coFilename) {
        // Ensure one of the two is in <<...>> format
        String internal = null;
        String external = null;
        if (coFilename.startsWith("<<") && coFilename.endsWith(">>")) {
            internal = coFilename;
            external = bpPath;
        } else if (bpPath.startsWith("<<") && bpPath.endsWith(">>")) {
            internal = bpPath;
            external = coFilename;
        }
        if (internal == null) return false;

        // Parse <<project/resource:func>>
        String body = internal.substring(2, internal.length() - 2);
        int colonIdx = body.lastIndexOf(':');
        if (colonIdx < 0) return false;

        String projectAndResource = body.substring(0, colonIdx); // e.g. "test-scripting/test"
        String funcName = body.substring(colonIdx + 1);          // e.g. "doGet"

        int slashIdx = projectAndResource.indexOf('/');
        if (slashIdx < 0) return false;

        String project = projectAndResource.substring(0, slashIdx);   // "test-scripting"
        String resource = projectAndResource.substring(slashIdx + 1); // "test"

        // Normalise the external path
        String norm = external.replace('\\', '/');

        // The external path should contain the project name and the function
        // name as the filename (e.g. doGet.py).  The resource should appear
        // as a directory component.
        return norm.contains("/" + project + "/")
                && norm.contains("/" + resource + "/")
                && norm.contains("/" + funcName + ".");
    }

    /** Remove all breakpoints for every file. */
    public void clear() {
        byFile.clear();
    }

    /** Returns all files that have at least one breakpoint. */
    public Map<String, List<Breakpoint>> getAllBreakpoints() {
        return Collections.unmodifiableMap(byFile);
    }

    // ---- Inner types -------------------------------------------------------

    /** A breakpoint as stored internally. */
    public static class Breakpoint {
        public final int id;
        public final int line;
        public final String condition;
        public boolean verified;

        public Breakpoint(int id, int line, String condition) {
            this.id = id;
            this.line = line;
            this.condition = condition;
        }
    }

    /** A breakpoint as requested from VS Code. */
    public static class BreakpointRequest {
        public int line;
        public String condition;

        public BreakpointRequest() {}
        public BreakpointRequest(int line, String condition) {
            this.line = line;
            this.condition = condition;
        }
    }
}
