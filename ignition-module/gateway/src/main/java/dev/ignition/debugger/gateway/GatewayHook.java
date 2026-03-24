package dev.ignition.debugger.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import dev.ignition.debugger.common.DebuggerConstants;
import dev.ignition.debugger.common.server.DebugWebSocketServer;
import dev.ignition.debugger.gateway.registry.GatewayRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

/**
 * Ignition Gateway module hook.
 *
 * <p>When the Gateway module starts, this hook:
 * <ol>
 *   <li>Starts a WebSocket server on an available local port.</li>
 *   <li>Generates a random shared secret for authentication.</li>
 *   <li>Writes a registry JSON file so the VS Code extension can discover
 *       this Gateway instance and debug Gateway and Perspective scripts.</li>
 * </ol>
 *
 * <p>When the module is unloaded, the server is stopped and the registry file
 * is removed.
 */
public class GatewayHook extends AbstractGatewayModuleHook {

    private static final Logger log = LoggerFactory.getLogger(GatewayHook.class);
    private static final int WEBSOCKET_SHUTDOWN_TIMEOUT_MS = 2000;

    private GatewayContext context;
    private DebugWebSocketServer wsServer;
    private final GatewayRegistry registry = new GatewayRegistry();

    // ---- Module lifecycle -------------------------------------------------

    @Override
    public void setup(GatewayContext ctx) {
        this.context = ctx;
    }

    @Override
    public void startup(LicenseState activationState) {
        log.info("{} (Gateway) starting up…", DebuggerConstants.MODULE_NAME);

        try {
            int port = resolvePort();
            String secret = UUID.randomUUID().toString();

            wsServer = new DebugWebSocketServer(port, secret);
            wsServer.start();

            writeRegistry(port, secret);
            log.info("{} (Gateway) started on port {}", DebuggerConstants.MODULE_NAME, port);
        } catch (Exception e) {
            log.error("Failed to start {} (Gateway): {}", DebuggerConstants.MODULE_NAME, e.getMessage(), e);
        }
    }

    @Override
    public void shutdown() {
        log.info("{} (Gateway) shutting down…", DebuggerConstants.MODULE_NAME);

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
            String ignitionVersion = resolveIgnitionVersion();

            registry.register(port, secret, gatewayHost, gatewayPort, gatewayName, ignitionVersion);
        } catch (IOException e) {
            log.error("Failed to write gateway registry file: {}", e.getMessage(), e);
        }
    }

    private String resolveGatewayHost() {
        return "localhost";
    }

    private int resolveGatewayPort() {
        try {
            return context.getWebResourceManager().getHttpPort();
        } catch (Exception e) {
            return 8088;
        }
    }

    private String resolveGatewayName() {
        try {
            return context.getSystemPropertiesManager().getSystemName();
        } catch (Exception e) {
            return "local";
        }
    }

    private String resolveIgnitionVersion() {
        return "8.3.x";
    }

    /**
     * Resolves the WebSocket port. If the {@code IGNITION_DEBUGGER_PORT} environment
     * variable is set to a valid port number, that port is used (allows Docker
     * deployments to expose a known fixed port). Otherwise a free port is chosen.
     */
    private static int resolvePort() throws IOException {
        String envPort = System.getenv("IGNITION_DEBUGGER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                int port = Integer.parseInt(envPort.trim());
                if (port > 0 && port <= 65535) {
                    return port;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return findFreePort();
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
