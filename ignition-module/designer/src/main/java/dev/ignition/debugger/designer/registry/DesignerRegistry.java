package dev.ignition.debugger.designer.registry;

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
 * to discover running Designer instances.
 *
 * <p>File location: {@code ~/{@link DebuggerConstants#REGISTRY_SUBDIR}/designer-{pid}.json}
 */
public class DesignerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DesignerRegistry.class);

    private final ObjectMapper mapper = new ObjectMapper();

    private Path registryFilePath;

    /**
     * Writes the registry file so VS Code can discover this Designer.
     *
     * @param port       WebSocket server port
     * @param secret     shared secret for authentication
     * @param gatewayHost gateway hostname
     * @param gatewayPort gateway HTTP port
     * @param gatewayName gateway name
     * @param projectName current project name
     * @param username    current user
     * @param ignitionVersion Ignition Designer version string
     */
    public void register(
            int port,
            String secret,
            String gatewayHost,
            int gatewayPort,
            String gatewayName,
            String projectName,
            String username,
            String ignitionVersion
    ) throws IOException {
        long pid = getPid();

        Path registryDir = Paths.get(
                System.getProperty("user.home"),
                ".ignition",
                DebuggerConstants.REGISTRY_SUBDIR
        );
        Files.createDirectories(registryDir);

        registryFilePath = registryDir.resolve(
                DebuggerConstants.REGISTRY_FILE_PREFIX + pid + DebuggerConstants.REGISTRY_FILE_SUFFIX
        );

        ObjectNode root = mapper.createObjectNode();
        root.put("pid", pid);
        root.put("port", port);
        root.put("startTime", Instant.now().toString());

        ObjectNode gateway = root.putObject("gateway");
        gateway.put("host", gatewayHost);
        gateway.put("port", gatewayPort);
        gateway.put("ssl", false);
        gateway.put("name", gatewayName != null ? gatewayName : "local");

        ObjectNode project = root.putObject("project");
        project.put("name", projectName != null ? projectName : "Unknown");
        project.put("title", projectName != null ? projectName : "Unknown");

        ObjectNode user = root.putObject("user");
        user.put("username", username != null ? username : "unknown");

        root.put("designerVersion", ignitionVersion != null ? ignitionVersion : "8.3.x");
        root.put("moduleVersion", DebuggerConstants.MODULE_VERSION);

        ObjectNode caps = root.putObject("capabilities");
        caps.put("scriptExecution", false);
        caps.put("gatewayScope", false);

        root.put("secret", secret);

        mapper.writerWithDefaultPrettyPrinter().writeValue(registryFilePath.toFile(), root);
        log.info("Ignition Debugger: registry file written to {}", registryFilePath);
    }

    /** Removes the registry file on shutdown. */
    public void unregister() {
        if (registryFilePath != null) {
            try {
                Files.deleteIfExists(registryFilePath);
                log.info("Ignition Debugger: registry file removed");
            } catch (IOException e) {
                log.warn("Ignition Debugger: could not remove registry file – {}", e.getMessage());
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
}
