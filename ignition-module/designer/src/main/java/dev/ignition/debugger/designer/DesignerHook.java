package dev.ignition.debugger.designer;

import com.inductiveautomation.ignition.client.launch.GatewayAddress;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import dev.ignition.debugger.common.DebuggerConstants;
import dev.ignition.debugger.designer.registry.DesignerRegistry;
import dev.ignition.debugger.common.server.DebugWebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

/**
 * Ignition Designer module hook.
 *
 * <p>When a Designer opens, this hook:
 * <ol>
 *   <li>Starts a WebSocket server on an available local port.</li>
 *   <li>Generates a random shared secret for authentication.</li>
 *   <li>Writes a registry JSON file so the VS Code extension can discover
 *       this Designer instance.</li>
 * </ol>
 *
 * <p>When the Designer closes (or the module is unloaded), the server is
 * stopped and the registry file is removed.
 */
public class DesignerHook extends AbstractDesignerModuleHook {

    private static final Logger log = LoggerFactory.getLogger(DesignerHook.class);
    private static final int WEBSOCKET_SHUTDOWN_TIMEOUT_MS = 2000;

    private DesignerContext context;
    private DebugWebSocketServer wsServer;
    private final DesignerRegistry registry = new DesignerRegistry();

    // ---- Module lifecycle -------------------------------------------------

    @Override
    public void startup(DesignerContext ctx, LicenseState activationState) throws Exception {
        this.context = ctx;
        log.info("{} starting up…", DebuggerConstants.MODULE_NAME);

        int port = findFreePort();
        String secret = UUID.randomUUID().toString();

        wsServer = new DebugWebSocketServer(port, secret);
        wsServer.start();

        writeRegistry(port, secret);
        log.info("{} started on port {}", DebuggerConstants.MODULE_NAME, port);
    }

    @Override
    public void shutdown() {
        log.info("{} shutting down…", DebuggerConstants.MODULE_NAME);

        registry.unregister();

        if (wsServer != null) {
            try {
                wsServer.stop(WEBSOCKET_SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            wsServer = null;
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private void writeRegistry(int port, String secret) {
        try {
            String gatewayHost = resolveGatewayHost();
            int gatewayPort = resolveGatewayPort();
            String gatewayName = resolveGatewayName();
            String projectName = resolveProjectName();
            String username = resolveUsername();
            String ignitionVersion = resolveIgnitionVersion();

            registry.register(
                    port, secret,
                    gatewayHost, gatewayPort, gatewayName,
                    projectName, username, ignitionVersion
            );
        } catch (IOException e) {
            log.error("Failed to write registry file: {}", e.getMessage(), e);
        }
    }

    private String resolveGatewayHost() {
        try {
            GatewayAddress addr = context.getLaunchContext().getGatewayAddress();
            return addr != null ? addr.getAddress() : "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }

    private int resolveGatewayPort() {
        try {
            GatewayAddress addr = context.getLaunchContext().getGatewayAddress();
            return addr != null ? addr.getPort() : 8088;
        } catch (Exception e) {
            return 8088;
        }
    }

    private String resolveGatewayName() {
        try {
            GatewayAddress addr = context.getLaunchContext().getGatewayAddress();
            return addr != null ? addr.getAddress() : "local";
        } catch (Exception e) {
            return "local";
        }
    }

    private String resolveProjectName() {
        try {
            return context.getProjectName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private String resolveUsername() {
        try {
            Object attr = context.getLaunchContext().getAttribute("username");
            return attr != null ? attr.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String resolveIgnitionVersion() {
        return "8.3.x";
    }

    /**
     * Finds a free TCP port on loopback.
     */
    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
