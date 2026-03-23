package dev.ignition.debugger.common;

/** Constants shared between all module components. */
public final class DebuggerConstants {

    private DebuggerConstants() {}

    /** Module ID as declared in module.xml */
    public static final String MODULE_ID = "dev.ignition.debugger";

    /** Human-readable name shown in the Gateway and Designer. */
    public static final String MODULE_NAME = "Ignition Debugger";

    /** Current module version. */
    public static final String MODULE_VERSION = "0.1.0";

    /**
     * Sub-directory inside {@code System.getProperty("user.home")/.ignition/}
     * where the designer registry files are written.
     */
    public static final String REGISTRY_SUBDIR = "debugger/designers";

    /** Prefix for registry file names: {@code designer-{pid}.json}. */
    public static final String REGISTRY_FILE_PREFIX = "designer-";

    /** Suffix for registry file names. */
    public static final String REGISTRY_FILE_SUFFIX = ".json";

    /** JSON-RPC version string used in every message. */
    public static final String JSONRPC_VERSION = "2.0";
}
