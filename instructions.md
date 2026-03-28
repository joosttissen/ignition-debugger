# Ignition Debugger – Agent Instructions

This file describes the intent of the project and how agents should understand and work with it.

---

## Project Intent

The **Ignition Debugger** is a proof-of-concept that bridges [Inductive Automation's Ignition SCADA platform](https://inductiveautomation.com/) with VS Code's debugger UI. It enables **step-level Jython debugging** of Ignition 8.3 scripts (running inside the Ignition Designer and Gateway) directly from VS Code, using the [Debug Adapter Protocol (DAP)](https://microsoft.github.io/debug-adapter-protocol/).

The goal is to give developers the same debugging experience (breakpoints, step over/into/out, variable inspection, expression evaluation) for Ignition Jython scripts that they expect from modern IDEs, in both launch mode and attach mode.

---

## Architecture

The project has two components that communicate over a local WebSocket using **JSON-RPC 2.0**:

```
VS Code Extension  <──WebSocket (127.0.0.1:{port})──>  Ignition Designer Module
(vscode-extension/)       JSON-RPC 2.0 + DAP events      (ignition-module/designer/)

VS Code Extension  <──WebSocket (127.0.0.1:{port})──>  Ignition Gateway Module
(vscode-extension/)       JSON-RPC 2.0 + DAP events      (ignition-module/gateway/)
```

### Discovery

1. When the Ignition Designer starts, the module writes a registry JSON file to `~/.ignition/debugger/designers/designer-{pid}.json`.
2. When the Ignition Gateway starts, the module writes a registry JSON file to `~/.ignition/debugger/gateway/gateway-{pid}.json`.
3. The VS Code extension reads both directories to discover available instances.
4. Authentication uses a random UUID secret stored in the registry file.

### Registry path override

Both sides support overriding the default registry directories:
- **Designer Module (Java):** set the `IGNITION_DEBUGGER_REGISTRY_DIR` environment variable.
- **Gateway Module (Java):** set the `IGNITION_DEBUGGER_GATEWAY_REGISTRY_DIR` environment variable.
- **Extension (TypeScript):** set the `ignition-debugger.registryPath` VS Code setting (Designer).
- **Extension (TypeScript):** set the `ignition-debugger.gatewayRegistryPath` VS Code setting (Gateway).

This override is essential when the Designer or Gateway runs in Docker (the registry directory must be bind-mounted to a path accessible from both the container and the host).

---

## Repository Structure

```
ignition-debugger/
├── README.md                   User-facing documentation
├── instructions.md             This file – agent guidance
├── docs/                       Full project docs (human + AI readable)
│   ├── README.md               Docs index
│   ├── architecture.md
│   ├── debug-protocol.md
│   ├── modes-and-breakpoints.md
│   ├── development-and-testing.md
│   └── troubleshooting.md
├── docker-compose.yml          Local Ignition gateway for development
├── ignition-debugger.code-workspace  Multi-root VS Code workspace
│
├── vscode-extension/           VS Code extension (TypeScript)
│   ├── package.json            Extension manifest (commands, debugger config, settings)
│   ├── tsconfig.json
│   └── src/
│       ├── extension.ts        Activation, status bar, commands
│       ├── debug/
│       │   └── IgnitionDebugAdapter.ts   DAP implementation (translates DAP ↔ JSON-RPC)
│       └── services/
│           ├── DiscoveryService.ts       Reads registry JSON files (designer + gateway)
│           └── ConnectionManager.ts      WebSocket + JSON-RPC client
│
└── ignition-module/            Ignition module (Java/Gradle)
    ├── build.gradle.kts        Root Gradle build (io.ia.sdk.modl plugin)
    ├── settings.gradle.kts     Gradle settings + repositories
    ├── gradlew / gradlew.bat   Gradle wrapper
    ├── common/                 Shared debug infrastructure (GD scope)
    │   └── src/main/java/dev/ignition/debugger/common/
    │       ├── DebuggerConstants.java      Module constants
    │       ├── protocol/                   JSON-RPC POJOs
    │       ├── debug/
    │       │   ├── JythonDebugger.java     sys.settrace-based step debugger
    │       │   └── BreakpointManager.java  Breakpoint storage
    │       └── server/
    │           └── DebugWebSocketServer.java  JSON-RPC WebSocket server
    ├── designer/               Designer-scoped module code (D scope)
    │   └── src/main/java/dev/ignition/debugger/designer/
    │       ├── DesignerHook.java           Module lifecycle entry point
    │       └── registry/
    │           └── DesignerRegistry.java   Writes/removes discovery JSON file
    └── gateway/                Gateway-scoped module code (G scope)
        └── src/main/java/dev/ignition/debugger/gateway/
            ├── GatewayHook.java            Module lifecycle entry point
            └── registry/
                └── GatewayRegistry.java    Writes/removes discovery JSON file
```

---

## Building

> **Use `bash build-and-test.sh` (see below) instead of building manually.**
> The sections below describe individual steps only for reference.

### VS Code Extension

```bash
cd vscode-extension
npm install
npm run compile          # TypeScript → out/
# Optional: package as .vsix
npx vsce package
```

### Ignition Module

Requires Java 11 and network access to the Inductive Automation Nexus repository.

```bash
cd ignition-module
./gradlew zipModule
```

The assembled (unsigned) module is placed at `ignition-module/build/ignition-debugger.unsigned.modl`.

Signing is disabled by default (`skipModlSigning = true` in `build.gradle.kts`).

---

## Build, Restart and Test – one command

> **IMPORTANT – agents and developers must always use this script.**
> Never build the module, restart Docker, or run the E2E tests with separate
> ad-hoc commands.  The script handles every step in the correct order,
> including clearing Ignition's module JAR cache (which would otherwise cause
> the old module code to keep running even after a rebuild).

```bash
# From the repo root – builds, restarts Docker, runs all E2E tests
bash build-and-test.sh
```

Options:

| Flag | Effect |
|------|--------|
| `--mock-only` | Skip Docker restart and Docker-mode test. Runs only the fast mock E2E test. Use for pure-logic changes that don't need the real gateway. |
| `--keep-running` | Leave the Docker container running after the test (default: leave it running). |

### What the script does

1. **Build** – runs `./gradlew zipModule` in `ignition-module/`.
2. **Mock E2E test** – runs the Node.js test suite in mock mode (no Docker needed).
3. **Clear JAR cache** – removes `ignition-data/jar-cache/dev.ignition.debugger/` inside
   the running container so Ignition extracts the new JARs from the updated `.modl`.
4. **Restart Docker** – runs `docker compose up -d` from the repo root.
5. **Wait for health** – polls `http://localhost:8088/StatusPing` (up to 3 min).
6. **Wait for registry** – polls `debugger-gateway-registry/gateway-*.json` (up to 90 s).
7. **Docker E2E test** – runs the Node.js test suite in Docker mode against the real gateway,
   including launch-mode, attach WebDev, and attach library-script suites.

### First-time module install

On a **fresh** Docker container the Ignition Debugger module must be installed once via the
Ignition web UI before the gateway writes its registry file:

1. Open <http://localhost:8088/web/config/modules>
2. Click **Install or Upgrade a Module**
3. Upload `ignition-module/build/ignition-debugger.unsigned.modl`
4. Accept the unsigned-module warning

After the first install the module persists across container restarts; `build-and-test.sh`
handles subsequent upgrades automatically via the JAR-cache-clear + restart cycle.

---

## Running Locally (manual)

The easiest way to run a local Ignition gateway is with Docker Compose:

```bash
docker compose up -d
```

The gateway starts at <http://localhost:8088> (credentials: `admin` / `password`).

The `docker-compose.yml` bind-mounts `./debugger-registry` and sets `IGNITION_DEBUGGER_REGISTRY_DIR` so the registry file is visible to the host. Set `ignition-debugger.registryPath` in VS Code to the same path.

---

## Testing (manual / individual steps)

### VS Code Extension

There is currently no automated test suite for the extension. Lint the TypeScript sources with:

```bash
cd vscode-extension
npm run lint
```

For manual end-to-end testing, use the *Extension Development Host* (press **F5** in VS Code with the `vscode-extension` folder open).

### Ignition Module

There are currently no automated unit tests for the Ignition module. Build verification is the primary check:

```bash
cd ignition-module
./gradlew zipModule
```

---

## Key Conventions

- **JSON-RPC 2.0** is the wire protocol for all Designer/Gateway ↔ VS Code communication. Every call from VS Code is a request (`id` present); every push from the Designer or Gateway is a notification (`id` absent).
- **DAP** (Debug Adapter Protocol) is used between VS Code and the extension. `IgnitionDebugAdapter` translates DAP requests into JSON-RPC calls and DAP events into JSON-RPC notifications.
- The **common module** (scope `"GD"`) contains shared debug infrastructure: `JythonDebugger`, `BreakpointManager`, and `DebugWebSocketServer`. Both Designer and Gateway scopes use these classes.
- Attach-mode tracing is installed globally in gateway contexts using `GlobalTraceInstaller` + `TracingThreadStateMapping`; repeated attach sessions must reuse the already-defined mapping class in the target classloader.
- Breakpoint filename matching must handle three formats: filesystem paths, Ignition WebDev internal format `<<project/resource:func>>`, and Ignition project-library format `<module:MODULE_PATH>`.
- The **designer module** (scope `"D"`) contains only `DesignerHook` and `DesignerRegistry`.
- The **gateway module** (scope `"G"`) contains only `GatewayHook` and `GatewayRegistry`.
- Module signing is skipped during development. Do **not** change `skipModlSigning` to `false` unless you have valid signing credentials.
- **TypeScript** targets ES2020 / Node 18+. Keep all new source files under `vscode-extension/src/`.
- **Java** source lives under `ignition-module/…/src/main/java/dev/ignition/debugger/`. Follow the existing package structure (`common.server`, `common.debug`, `designer.registry`, `gateway.registry`).

---

## Known Limitations (POC)

- The module JAR is not code-signed (Ignition will display a warning on install).
- Conditional breakpoints are parsed but not evaluated.
- Attach-mode E2E tests are Docker-only and depend on the WebDev endpoint and project library script in `ignition-data/projects/test-scripting/`.

---

## Contributing

Open an issue before starting significant changes. Pull requests are welcome.
