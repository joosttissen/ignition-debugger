package dev.ignition.debugger.gateway.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ignition.debugger.common.DebuggerConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

/**
 * Writes and removes the registry JSON file that the VS Code extension reads
 * to discover running Gateway instances.
 *
 * <p>File location: {@code ~/{@link DebuggerConstants#GATEWAY_REGISTRY_SUBDIR}/gateway-{pid}.json}
 *
 * <p>When the environment variable {@link DebuggerConstants#GATEWAY_REGISTRY_DIR_ENV}
 * is set, its value is used as the registry directory instead of the default
 * {@code ~/.ignition/debugger/gateway}.
 */
public class GatewayRegistry {

    private static final Logger log = LoggerFactory.getLogger(GatewayRegistry.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private Path registryFilePath;

    /**
     * Writes the registry file so VS Code can discover this Gateway.
     *
     * @param port            WebSocket server port
     * @param secret          shared secret for authentication
     * @param gatewayHost     gateway hostname
     * @param gatewayPort     gateway HTTP port
     * @param gatewayName     gateway name
     * @param ignitionVersion Ignition Gateway version string
     */
    public void register(
            int port,
            String secret,
            String gatewayHost,
            int gatewayPort,
            String gatewayName,
            String ignitionVersion
    ) throws IOException {
        long pid = getPid();

        Path registryDir = resolveRegistryDir();
        Files.createDirectories(registryDir);

        registryFilePath = registryDir.resolve(
                DebuggerConstants.GATEWAY_REGISTRY_FILE_PREFIX + pid + DebuggerConstants.REGISTRY_FILE_SUFFIX
        );

        ObjectNode root = mapper.createObjectNode();
        root.put("pid", pid);
        root.put("port", port);
        root.put("scope", "gateway");
        root.put("startTime", Instant.now().toString());

        ObjectNode gateway = root.putObject("gateway");
        gateway.put("host", gatewayHost != null ? gatewayHost : "localhost");
        gateway.put("port", gatewayPort);
        gateway.put("ssl", false);
        gateway.put("name", gatewayName != null ? gatewayName : "local");

        root.put("ignitionVersion", ignitionVersion != null ? ignitionVersion : "8.3.x");
        root.put("moduleVersion", DebuggerConstants.MODULE_VERSION);

        root.put("secret", secret);

        mapper.writerWithDefaultPrettyPrinter().writeValue(registryFilePath.toFile(), root);
        log.info("Ignition Debugger: gateway registry file written to {}", registryFilePath);
    }

    /** Removes the registry file on shutdown. */
    public void unregister() {
        if (registryFilePath != null) {
            try {
                Files.deleteIfExists(registryFilePath);
                log.info("Ignition Debugger: gateway registry file removed");
            } catch (IOException e) {
                log.warn("Ignition Debugger: could not remove gateway registry file – {}", e.getMessage());
            }
            registryFilePath = null;
        }
    }

    private static long getPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName(); // "pid@hostname"
        try {
            return Long.parseLong(name.split("@")[0]);
        } catch (NumberFormatException e) {
            return ProcessHandle.current().pid();
        }
    }

    /**
     * Returns the gateway registry directory.  If the environment variable
     * {@link DebuggerConstants#GATEWAY_REGISTRY_DIR_ENV} is set, its value is used as
     * the directory path; otherwise falls back to the default
     * {@code ~/.ignition/debugger/gateway}.
     */
    static Path resolveRegistryDir() {
        String envDir = System.getenv(DebuggerConstants.GATEWAY_REGISTRY_DIR_ENV);
        if (envDir != null && !envDir.isBlank()) {
            return Paths.get(envDir);
        }
        return Paths.get(
                System.getProperty("user.home"),
                ".ignition",
                DebuggerConstants.GATEWAY_REGISTRY_SUBDIR
        );
    }
}
