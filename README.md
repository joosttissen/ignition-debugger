# Ignition Debugger

A proof-of-concept project that enables **step-level Jython debugging** of Ignition 8.3
scripts directly from VS Code.

---

## Architecture

The project consists of two components that communicate over a local WebSocket
using **JSON-RPC 2.0**:

```
┌─────────────────────────┐          WebSocket (127.0.0.1:{port})         ┌──────────────────────────────┐
│  VS Code Extension      │◄──────────────────────────────────────────────►│  Ignition Designer Module    │
│  (vscode-extension/)    │          JSON-RPC 2.0 + DAP events             │  (ignition-module/designer/) │
│                         │                                                 │                              │
│  IgnitionDebugAdapter   │  authenticate, debug.startSession,             │  DebugWebSocketServer        │
│  (implements DAP)       │  debug.setBreakpoints, debug.run, …            │  (handles JSON-RPC)          │
│                         │                                                 │                              │
│  ConnectionManager      │◄── debug.event.stopped / output / terminated ──│  JythonDebugger              │
│  (WebSocket client)     │                                                 │  (sys.settrace based)        │
│                         │                                                 │                              │
│  DiscoveryService       │  reads ~/.ignition/debugger/designers/         │  DesignerRegistry            │
│  (reads registry)       │◄────────────────────────────────────────────── │  (writes registry file)      │
└─────────────────────────┘                                                 └──────────────────────────────┘
```

### Discovery Flow

1. The Ignition Designer module writes a registry JSON file to
   `~/.ignition/debugger/designers/designer-{pid}.json` on startup.
2. The VS Code extension reads that directory to discover running Designer instances.
3. The user (or auto-connect logic) selects a Designer and connects via WebSocket.
4. Authentication uses a random UUID secret written to the registry file.

### Debug Protocol

Commands from VS Code → Designer:

| JSON-RPC method        | Description                              |
|------------------------|------------------------------------------|
| `authenticate`         | Verify the shared secret                 |
| `ping`                 | Connectivity check                       |
| `debug.startSession`   | Compile & register a script for debugging|
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

Events pushed from Designer → VS Code (as JSON-RPC notifications):

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
├── vscode-extension/           VS Code extension (TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
│       ├── extension.ts        Activation, commands, status bar
│       ├── debug/
│       │   └── IgnitionDebugAdapter.ts   DAP implementation
│       └── services/
│           ├── DiscoveryService.ts       Reads registry files
│           └── ConnectionManager.ts      WebSocket + JSON-RPC client
│
└── ignition-module/            Ignition module (Java/Maven)
    ├── pom.xml                 Root Maven POM
    ├── common/                 Shared protocol POJOs (JsonRpc*)
    └── designer/               Designer module
        └── src/main/java/dev/ignition/debugger/designer/
            ├── DesignerHook.java           Module entry point
            ├── server/
            │   └── DebugWebSocketServer.java   JSON-RPC WS server
            ├── debug/
            │   ├── JythonDebugger.java      sys.settrace debugger
            │   └── BreakpointManager.java   Breakpoint storage
            └── registry/
                └── DesignerRegistry.java    Writes discovery file
```

---

## Getting Started

### Prerequisites

- **Docker** and **Docker Compose** (recommended for local development)
- **VS Code** 1.75+
- **Java 11** (for building the Ignition module)
- **Node.js 18+** (for building the VS Code extension)
- **Maven 3.8+**

### 0 – Start Ignition with Docker Compose

The easiest way to get an Ignition 8.3 gateway running locally is with Docker
Compose. The included `docker-compose.yml` mounts the Ignition `data/` directory
to `./ignition-data` so that project scripts are directly accessible from VS Code.

```bash
docker compose up -d
```

The gateway will be available at <http://localhost:8088> once it finishes
starting (this can take a minute or two on first launch). The default
credentials are `admin` / `password`.

> **Docker & the registry file** – The debugger module writes a registry JSON
> file to `~/.ignition/debugger/designers/` so the VS Code extension can
> discover running Designers.  When the Designer is launched inside a Docker
> container, that path lives inside the container and is invisible to the host.
>
> The `docker-compose.yml` handles this by:
> 1. Bind-mounting `./debugger-registry` into the container.
> 2. Setting the `IGNITION_DEBUGGER_REGISTRY_DIR` environment variable to
>    point the module at the mounted path.
>
> On the VS Code side, set the **Ignition Debugger: Registry Path** setting
> (`ignition-debugger.registryPath`) to the same host directory
> (e.g. `./debugger-registry`) so the extension can find the registry files.

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
> Maven repository.  This requires network access or a local mirror.

```bash
cd ignition-module
mvn package -DskipTests
```

The signed module artifact will be at
`designer/target/ignition-debugger-designer-0.1.0-SNAPSHOT.jar`.

To package it as a `.modl` file (zip of the JAR + `module.xml`):

```bash
cd designer/target
cp ../src/main/resources/module.xml .
zip -r ignition-debugger-0.1.0.modl module.xml ignition-debugger-designer-0.1.0-SNAPSHOT.jar
```

### 3 – Install the Ignition Module

1. Open the Ignition Gateway web page → **Config** → **Modules**.
2. Click **Install or Upgrade a Module** and upload `ignition-debugger-0.1.0.modl`.
3. Accept the unsigned module warning (development builds are not signed).
4. Open (or restart) a **Designer** for the module to activate.

### 4 – Debug a Script

1. Click the `$(plug) Ignition: Disconnected` item in the VS Code status bar.
2. Select the running Designer from the quick-pick.
3. Open a Python (`.py`) file that corresponds to an Ignition script.
4. Set breakpoints by clicking in the gutter.
5. Press **F5** and choose the *Ignition Jython Debugger* configuration.
6. Execute the script in the Designer to hit your breakpoints.

---

## Features

- **Breakpoints** – set and verify line breakpoints in Python scripts
- **Step debugging** – Step Over (`F10`), Step Into (`F11`), Step Out (`⇧F11`)
- **Variable inspection** – Locals and Globals panels at every pause
- **Debug console** – evaluate Python expressions in the current frame
- **Output capture** – `print()` and stderr appear in the VS Code Debug Console

## Known Limitations (POC)

- Only the **Designer** scope is supported; Gateway and Perspective scopes are
  not yet wired up.
- The module JAR is not code-signed (Ignition will warn on install).
- Conditional breakpoints are parsed but not yet evaluated.
- The `.modl` packaging step must be done manually (no Maven plugin configured).

---

## Contributing

Pull requests welcome!  Please open an issue first to discuss significant changes.

## License

MIT