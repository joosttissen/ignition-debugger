# CLAUDE.md – Ignition Debugger

## What is this project?

Ignition Debugger: a VS Code extension + Ignition Java module enabling step-level Jython debugging of Ignition 8.3 scripts from VS Code. Two components communicate via JSON-RPC 2.0 over WebSocket; VS Code ↔ extension uses DAP.

Full context: `instructions.md` (root). Architecture and protocol: `docs/`.

## Repository structure

```
vscode-extension/           TypeScript VS Code extension (DAP adapter)
ignition-module/            Java/Gradle Ignition module
  common/                   Shared debug infra (scope GD): JythonDebugger, BreakpointManager, DebugWebSocketServer
  designer/                 Designer scope (D): DesignerHook, DesignerRegistry
  gateway/                  Gateway scope (G): GatewayHook, GatewayRegistry
e2e-test/                   Node.js E2E tests (mock + Docker)
ignition-data/              Ignition project resources for testing
docs/                       Project documentation
```

## Build and test

```bash
# Always use this — builds module, runs mock + Docker E2E tests
bash build-and-test.sh

# Mock-only (no Docker needed)
bash build-and-test.sh --mock-only
```

Manual (reference only):
- Extension: `cd vscode-extension && npm install && npm run compile`
- Module: `cd ignition-module && ./gradlew zipModule`
- Lint: `cd vscode-extension && npm run lint`
- E2E: `cd e2e-test && npm install && npm test`

## Code conventions

- **TypeScript**: ES2020, Node 18+, strict mode. All source under `vscode-extension/src/`.
- **Java**: Java 11, package `dev.ignition.debugger.*`. Gradle + `io.ia.sdk.modl` plugin, Ignition SDK 8.3.0. Module signing disabled for dev.
- **Protocol**: JSON-RPC 2.0 (extension ↔ module), DAP (VS Code ↔ extension). Requests have `id`; notifications do not.
- **Authentication**: UUID secret from registry files at `~/.ignition/debugger/`.

## Debug modes

- **Launch**: `debug.startSession` → `debug.setBreakpoints` → `debug.run` → events → `debug.stopSession`
- **Attach**: `debug.attach` → `debug.setBreakpoints` → external trigger → events → `debug.detach`

## Breakpoint filename matching

1. Filesystem path suffix match
2. WebDev internal: `<<project/resource:func>>`
3. Project library: `<module:MODULE_PATH>`

## Ignition platform knowledge

- Ignition is a SCADA/IIoT platform by Inductive Automation. Scripts are Jython (Python 2.7 on JVM).
- **Designer** = project IDE. **Gateway** = server runtime.
- Module scopes: G (Gateway), D (Designer), C (Client). This project uses G, D, GD.
- Module lifecycle via Hook classes (`startup()` / `shutdown()`).
- WebDev module provides HTTP endpoints backed by Python scripts.
- Project library scripts are shared modules (`<module:path>` filename format).
- `ScriptManager.runCode()` throws checked `JythonExecException` — always wrap in try-catch.

## Key invariants

- Re-attach must not redefine `TracingThreadStateMapping` if class already exists in classloader.
- Registry files are written on startup, removed on shutdown.
- JAR cache must be cleared when upgrading module in Docker (`ignition-data/jar-cache/dev.ignition.debugger/`).
- Module artifact: `ignition-module/build/ignition-debugger.unsigned.modl`.

## Useful references

- `instructions.md` — full agent instructions
- `docs/architecture.md` — component roles and discovery
- `docs/debug-protocol.md` — JSON-RPC method details
- `docs/sequence-diagrams.md` — ordered message flows
- `docs/modes-and-breakpoints.md` — attach/launch behavior
- `docs/development-and-testing.md` — build and test procedures
- `docs/troubleshooting.md` — common failures and fixes
- `docs/reference.md` — DAP ↔ JSON-RPC mapping and invariants
- `docs/ai-context.md` — machine-optimized canonical context
