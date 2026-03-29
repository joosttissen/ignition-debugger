# Copilot Instructions – Ignition Debugger

## Project overview

Ignition Debugger is a VS Code extension + Ignition Java module that enables step-level Jython debugging of Ignition 8.3 scripts from VS Code. It uses DAP (VS Code ↔ extension) and JSON-RPC 2.0 over WebSocket (extension ↔ Ignition module).

For full project context, read `instructions.md` at the repo root. Detailed architecture, protocol, and testing docs are in the `docs/` directory.

## Repository layout

- `vscode-extension/` – TypeScript VS Code extension (DAP adapter, discovery, connection management).
- `ignition-module/` – Java/Gradle Ignition module with three sub-projects:
  - `common/` (scope GD) – shared debug infrastructure: `JythonDebugger`, `BreakpointManager`, `DebugWebSocketServer`, `GlobalTraceInstaller`.
  - `designer/` (scope D) – `DesignerHook` + `DesignerRegistry`.
  - `gateway/` (scope G) – `GatewayHook` + `GatewayRegistry`.
- `e2e-test/` – Node.js/TypeScript E2E test suites (mock + Docker modes).
- `ignition-data/` – Ignition project resources used for testing.
- `docs/` – architecture, protocol, testing, troubleshooting docs.

## Build and test

Always use the unified build-and-test script from the repo root:

```bash
bash build-and-test.sh            # full build + mock E2E + Docker E2E
bash build-and-test.sh --mock-only # mock E2E only (no Docker)
```

Manual steps (reference only):
- Extension: `cd vscode-extension && npm install && npm run compile`
- Module: `cd ignition-module && ./gradlew zipModule`
- E2E: `cd e2e-test && npm install && npm test`

## Code conventions

### TypeScript (vscode-extension/)
- Target ES2020 / Node 18+.
- All source in `vscode-extension/src/`.
- Strict TypeScript enabled.
- Lint with `npm run lint`.

### Java (ignition-module/)
- Java 11 source level.
- Package root: `dev.ignition.debugger`.
- Sub-packages follow scope: `common.server`, `common.debug`, `common.protocol`, `designer.registry`, `gateway.registry`.
- Gradle build with `io.ia.sdk.modl` plugin (v0.5.0), Ignition SDK 8.3.0.
- Module signing is disabled for development (`skipModlSigning = true`).

## Protocol

- **JSON-RPC 2.0**: all extension ↔ module communication. Requests have `id`; notifications have no `id`.
- **DAP**: VS Code ↔ extension. `IgnitionDebugAdapter` translates DAP ↔ JSON-RPC.
- Authentication uses a random UUID secret from registry files at `~/.ignition/debugger/`.

## Debug modes

- **Launch mode**: `debug.startSession` → `debug.setBreakpoints` → `debug.run` → events → `debug.stopSession`.
- **Attach mode**: `debug.attach` → `debug.setBreakpoints` → external trigger → events → `debug.detach`.

## Breakpoint filename matching

Three strategies for matching breakpoints to runtime `co_filename`:
1. Filesystem path suffix match.
2. Ignition WebDev internal format: `<<project/resource:func>>`.
3. Ignition project library format: `<module:MODULE_PATH>`.

## Ignition platform context

- **Ignition** is a SCADA/IIoT platform by Inductive Automation. Scripts run as Jython (Python 2.7 on JVM).
- **Designer** is the IDE for building Ignition projects. **Gateway** is the server runtime.
- Ignition modules have scopes: G (Gateway), D (Designer), C (Client). This project uses G, D, and GD (shared).
- Module lifecycle is managed by Hook classes (`startup()`, `shutdown()`).
- WebDev module provides HTTP endpoints backed by Python scripts.
- Project library scripts are shared modules accessible as `<module:path>`.

## Key invariants

- `ScriptManager.runCode()` throws checked `JythonExecException`; always catch it.
- Re-attach must not redefine `TracingThreadStateMapping` if already in classloader.
- Registry files are cleaned up on module shutdown.
- The JAR cache must be cleared when upgrading the module in Docker.
