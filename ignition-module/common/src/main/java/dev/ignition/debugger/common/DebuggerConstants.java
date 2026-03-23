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

    /**
     * Environment variable that, when set, overrides the default registry
     * directory ({@code ~/.ignition/debugger/designers}).  This is useful when
     * the Designer runs inside a Docker container and the registry directory
     * must be a bind-mounted volume that the host VS Code extension can read.
     */
    public static final String REGISTRY_DIR_ENV = "IGNITION_DEBUGGER_REGISTRY_DIR";

    /**
     * Sub-directory inside {@code System.getProperty("user.home")/.ignition/}
     * where the gateway registry files are written.
     */
    public static final String GATEWAY_REGISTRY_SUBDIR = "debugger/gateway";

    /** Prefix for gateway registry file names: {@code gateway-{pid}.json}. */
    public static final String GATEWAY_REGISTRY_FILE_PREFIX = "gateway-";

    /**
     * Environment variable that, when set, overrides the default gateway registry
     * directory ({@code ~/.ignition/debugger/gateway}).
     */
    public static final String GATEWAY_REGISTRY_DIR_ENV = "IGNITION_DEBUGGER_GATEWAY_REGISTRY_DIR";

    /** JSON-RPC version string used in every message. */
    public static final String JSONRPC_VERSION = "2.0";
}
