package dev.ignition.debugger.designer.debug;

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
     */
    public boolean isBreakpoint(String filePath, int line) {
        List<Breakpoint> bps = byFile.get(filePath);
        if (bps == null) {
            return false;
        }
        for (Breakpoint bp : bps) {
            if (bp.line == line) {
                return true;
            }
        }
        return false;
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
