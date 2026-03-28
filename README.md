# Ignition Debugger

A proof-of-concept project that enables **step-level Jython debugging** of Ignition 8.3
scripts directly from VS Code, in both launch mode and attach mode.

---

## Architecture

Extended documentation is available in [docs/README.md](docs/README.md).

The project consists of two components that communicate over a local WebSocket
using **JSON-RPC 2.0**:

```
┌─────────────────────────┐     WebSocket (127.0.0.1:{port})      ┌──────────────────────────────┐
│  VS Code Extension      │◄─────────────────────────────────────►│  Ignition Designer Module    │
│  (vscode-extension/)    │       JSON-RPC 2.0 + DAP events        │  (ignition-module/designer/) │
│                         │                                         └──────────────────────────────┘
│  IgnitionDebugAdapter   │
│  (implements DAP)       │     WebSocket (127.0.0.1:{port})      ┌──────────────────────────────┐
│                         │◄─────────────────────────────────────►│  Ignition Gateway Module     │
│  ConnectionManager      │       JSON-RPC 2.0 + DAP events        │  (ignition-module/gateway/)  │
│  (WebSocket client)     │                                         └──────────────────────────────┘
│                         │
│  DiscoveryService       │  reads ~/.ignition/debugger/designers/   (Designer registry files)
│  (reads registry)       │  reads ~/.ignition/debugger/gateway/     (Gateway registry files)
└─────────────────────────┘
```

### Discovery Flow

1. The Ignition **Designer** module writes a registry JSON file to
   `~/.ignition/debugger/designers/designer-{pid}.json` on startup.
2. The Ignition **Gateway** module writes a registry JSON file to
   `~/.ignition/debugger/gateway/gateway-{pid}.json` on startup.
3. The VS Code extension reads both directories to discover running instances.
4. The user selects an instance (Designer or Gateway) and connects via WebSocket.
5. Authentication uses a random UUID secret written to the registry file.

### Debug Protocol

Commands from VS Code → Designer/Gateway:

| JSON-RPC method        | Description                              |
|------------------------|------------------------------------------|
| `authenticate`         | Verify the shared secret                 |
| `ping`                 | Connectivity check                       |
| `debug.startSession`   | Compile & register a script for debugging|
| `debug.attach`         | Attach debugger to running gateway/designer context |
| `debug.detach`         | Detach attach-mode session               |
| `debug.run`            | Start executing (after breakpoints set)  |
| `debug.stopSession`    | Abort the current session                |
| `debug.setBreakpoints` | Set breakpoints for a file               |
| `debug.getStackTrace`  | Return stack frames at the current pause |
| `debug.getScopes`      | Return variable scopes for a frame       |
| `debug.getVariables`   | Return variables for a scope handle      |
| `debug.continue`       | Resume execution                         |
| `debug.stepOver`       | Step over the current line               |
| `debug.stepInto`       | Step into the next call                  |
| `debug.stepOut`        | Step out of the current frame            |
| `debug.pause`          | Request a pause (best-effort)            |
| `debug.evaluate`       | Evaluate an expression in current frame  |

Events pushed from Designer/Gateway → VS Code (as JSON-RPC notifications):

| Notification method            | Description                          |
|--------------------------------|--------------------------------------|
| `debug.event.stopped`          | Script paused at breakpoint or step  |
| `debug.event.output`           | `print()` output or stderr           |
| `debug.event.terminated`       | Script finished                      |
| `debug.event.exited`           | Process exit with code               |

---

## Repository Structure

```
ignition-debugger/
├── docs/                       Full project documentation
│   ├── README.md               Docs index
│   ├── architecture.md         Component architecture
│   ├── sequence-diagrams.md    Numbered launch/attach message flows
│   ├── debug-protocol.md       JSON-RPC and event flows
│   ├── reference.md            Method mapping and invariants
│   ├── modes-and-breakpoints.md Attach/launch behavior and filename matching
│   ├── development-and-testing.md Build/test operations
│   ├── troubleshooting.md      Common failures and fixes
│   ├── glossary.md             Shared terminology
│   └── ai-context.md           Machine-oriented canonical context
│
├── vscode-extension/           VS Code extension (TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── extension.ts        Activation, commands, status bar
│       ├── debug/
│       │   └── IgnitionDebugAdapter.ts   DAP implementation
│       └── services/
│           ├── DiscoveryService.ts       Reads registry files (designer + gateway)
│           └── ConnectionManager.ts      WebSocket + JSON-RPC client
│
└── ignition-module/            Ignition module (Java/Gradle)
    ├── build.gradle.kts        Root Gradle build (io.ia.sdk.modl plugin)
    ├── settings.gradle.kts     Gradle settings (subprojects + repositories)
    ├── gradlew / gradlew.bat   Gradle wrapper scripts
    ├── common/                 Shared debug infrastructure (GD scope)
    │   └── src/main/java/dev/ignition/debugger/common/
    │       ├── DebuggerConstants.java      Shared constants
    │       ├── protocol/                   JSON-RPC POJOs
    │       ├── debug/
    │       │   ├── JythonDebugger.java     sys.settrace debugger
    │       │   └── BreakpointManager.java  Breakpoint storage
    │       └── server/
    │           └── DebugWebSocketServer.java  JSON-RPC WS server
    ├── designer/               Designer module (D scope)
    │   └── src/main/java/dev/ignition/debugger/designer/
    │       ├── DesignerHook.java           Module entry point
    │       └── registry/
    │           └── DesignerRegistry.java   Writes discovery file
    └── gateway/                Gateway module (G scope)
        └── src/main/java/dev/ignition/debugger/gateway/
            ├── GatewayHook.java            Module entry point
            └── registry/
                └── GatewayRegistry.java    Writes discovery file
```

---

## Getting Started

### Prerequisites

- **Docker** and **Docker Compose** (recommended for local development)
- **VS Code** 1.75+
- **Java 11** (for building the Ignition module)
- **Node.js 18+** (for building the VS Code extension)

### 0 – Start Ignition with Docker Compose

The easiest way to get an Ignition 8.3 gateway running locally is with Docker
Compose. The included `docker-compose.yml` uses a named Docker volume for the
Ignition `data/` directory and bind-mounts the `projects/` subdirectory to
`./ignition-data/projects` so that project scripts are directly accessible from
VS Code. A one-shot init service (`init-projects-dir`) runs before the gateway
to ensure the bind-mounted directory has the correct ownership (UID 2003) so
Ignition can create its internal `.resources` directory.

```bash
docker compose up -d
```

The gateway will be available at <http://localhost:8088> once it finishes
starting (this can take a minute or two on first launch). The default
credentials are `admin` / `password`.

> **Docker & the registry files** – The debugger module writes registry JSON
> files so the VS Code extension can discover running instances.  When
> running inside a Docker container, those paths live inside the container
> and are invisible to the host.
>
> The `docker-compose.yml` handles this by:
> 1. Bind-mounting `./debugger-registry` for Designer registry files.
> 2. Bind-mounting `./debugger-gateway-registry` for Gateway registry files.
> 3. Setting `IGNITION_DEBUGGER_REGISTRY_DIR` and
>    `IGNITION_DEBUGGER_GATEWAY_REGISTRY_DIR` to point the module at the
>    mounted paths.
>
> On the VS Code side:
> - Set `ignition-debugger.registryPath` to `./debugger-registry`.
> - Set `ignition-debugger.gatewayRegistryPath` to `./debugger-gateway-registry`.

> **Tip:** Open the included `ignition-debugger.code-workspace` in VS Code
> (`File → Open Workspace from File…`) to get a multi-root workspace that
> includes both the source code and the Ignition project files.  The
> *Ignition Projects* folder will appear once the container has started and
> created its data directory.

### 1 – Build the VS Code Extension

```bash
cd vscode-extension
npm install
npm run compile
# To package as .vsix:
# npx vsce package
```

Install the extension in VS Code:
- Open VS Code → Extensions → `...` → *Install from VSIX…*

### 2 – Build the Ignition Module

> **Note:** The Ignition SDK JARs are fetched from the Inductive Automation
> Nexus repository.  This requires network access or a local mirror.

```bash
cd ignition-module
./gradlew build
```

The assembled unsigned `.modl` file will be at
`build/ignition-debugger.unsigned.modl`.

> **Tip:** The build is pre-configured with `skipModlSigning = true` for
> development.  To produce a signed module, set `skipModlSigning.set(false)` in
> `build.gradle.kts` and provide your signing credentials (see
> [the plugin README](https://github.com/inductiveautomation/ignition-module-tools/blob/master/gradle-module-plugin/README.md)
> for details).

### 3 – Install the Ignition Module

1. Open the Ignition Gateway web page → **Config** → **Modules**.
2. Click **Install or Upgrade a Module** and upload `ignition-debugger.unsigned.modl` from `ignition-module/build/`.
3. Accept the unsigned module warning (development builds are not signed).
4. Open (or restart) a **Designer** for the Designer module to activate.
5. The **Gateway** module activates automatically when the module is installed.

### 4 – Debug a Script

1. Click the `$(plug) Ignition: Disconnected` item in the VS Code status bar.
2. Select a running **Designer** or **Gateway** instance from the quick-pick.
3. Open a Python (`.py`) file that corresponds to an Ignition script.
4. Set breakpoints by clicking in the gutter.
5. Press **F5** and choose the *Ignition Jython Debugger* configuration.
6. Execute the script to hit your breakpoints.

   - For **Designer** scripts: run the script inside the Designer.
   - For **Gateway/Perspective** scripts: trigger the script from the gateway
     (e.g. via a tag event, timer, or by running it through the debug session).

---

## End-to-End Test

The `e2e-test/` directory contains a self-contained end-to-end test suite that proves:

- Launch-mode debugging of gateway scripts.
- Attach-mode debugging of WebDev endpoint scripts.
- Attach-mode debugging of project library scripts executed from WebDev.

### Quick run (no Docker needed)

```bash
cd e2e-test
npm install
npm test
```

This starts a Node.js mock of the Ignition Gateway WebSocket debug server and runs both:

- Launch-mode flow (`startSession` + `run`).
- Attach-mode flow (`attach` + breakpoint hit + inspect + continue + detach).

### Full Docker run (real Ignition gateway)

```bash
# 1. Build the Ignition module
cd ignition-module && ./gradlew build

# 2. Start the gateway
docker compose up -d

# 3. Install the module at http://localhost:8088/web/config/modules
#    (upload ignition-module/build/ignition-debugger.unsigned.modl)

# 4. Run the E2E test
cd e2e-test && bash run-e2e-test.sh
```

See [e2e-test/README.md](e2e-test/README.md) for full details.

Current Docker coverage includes:

- Launch-mode script debugging (`gateway_scripts/code.py`).
- Attach-mode WebDev debugging (`doGet.py`, internal filename format `<<project/resource:func>>`).
- Attach-mode library script debugging (`gateway_scripts.greet()`, internal filename format `<module:MODULE_PATH>`).

### Breakpoint Path Matching

Breakpoint matching supports all filename shapes observed in Ignition/Jython:

- Filesystem paths (host/container path differences handled by suffix matching).
- WebDev/internal compiled functions: `<<project/resource:func>>`.
- Project library modules: `<module:MODULE_PATH>` (for example `<module:gateway_scripts>`).

### Test project

The test debugs `ignition-data/projects/test-scripting/ignition/script-python/gateway_scripts/code.py`,
a Python script that is automatically available as a project script library when the
Docker gateway starts. The WebDev endpoint test resource also calls `gateway_scripts.greet()`
to validate attach-mode library breakpoints.

---

## Features

- **Breakpoints** – set and verify line breakpoints across filesystem, `<<project/resource:func>>`, and `<module:MODULE_PATH>` filename formats
- **Step debugging** – Step Over (`F10`), Step Into (`F11`), Step Out (`⇧F11`)
- **Variable inspection** – Locals and Globals panels at every pause
- **Debug console** – evaluate Python expressions in the current frame
- **Output capture** – `print()` and stderr appear in the VS Code Debug Console
- **Attach mode** – attach to running Gateway/Designer script execution contexts
- **Designer scope** – debug scripts running in the Ignition Designer
- **Gateway scope** – debug Gateway scripts (event scripts, timer scripts, etc.)
- **Perspective scope** – debug Perspective component scripts running on the gateway

## Known Limitations (POC)

- The module JAR is not code-signed (Ignition will warn on install).
- Conditional breakpoints are parsed but not yet evaluated.

---

## Contributing

Pull requests welcome!  Please open an issue first to discuss significant changes.

## License

MIT