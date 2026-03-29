# Architecture

## Purpose

Ignition Debugger enables step-level debugging of Ignition Jython scripts from VS Code.

The system bridges two protocols:

- DAP between VS Code and the extension.
- JSON-RPC 2.0 between the extension and Ignition module endpoints.

For numbered end-to-end message order and lifecycle transitions, see [sequence-diagrams.md](sequence-diagrams.md).

## High-Level Components

1. VS Code Extension (`vscode-extension/`)
2. Ignition Module (`ignition-module/`)
3. Ignition Project resources (`ignition-data/projects/`)
4. E2E harness (`e2e-test/`)

## Component Roles

### VS Code Extension

Location: `vscode-extension/src/`

Key responsibilities:

- Registers the debugger type and launch configuration behavior.
- Translates DAP requests/events to JSON-RPC calls/notifications.
- Discovers running Designer/Gateway instances from registry files.
- Maintains socket connection lifecycle.

Primary files:

- `vscode-extension/src/extension.ts`
- `vscode-extension/src/debug/IgnitionDebugAdapter.ts`
- `vscode-extension/src/services/DiscoveryService.ts`
- `vscode-extension/src/services/ConnectionManager.ts`

### Ignition Module

Location: `ignition-module/`

Submodules:

- `common` (shared core logic)
- `designer` (Designer scope integration)
- `gateway` (Gateway scope integration)

Common module key files:

- `ignition-module/common/src/main/java/dev/ignition/debugger/common/server/DebugWebSocketServer.java`
- `ignition-module/common/src/main/java/dev/ignition/debugger/common/debug/JythonDebugger.java`
- `ignition-module/common/src/main/java/dev/ignition/debugger/common/debug/BreakpointManager.java`
- `ignition-module/common/src/main/java/dev/ignition/debugger/common/debug/GlobalTraceInstaller.java`
- `ignition-module/common/src/main/java/org/python/core/TracingThreadStateMapping.java`

Designer/Gateway registry writers:

- `ignition-module/designer/src/main/java/dev/ignition/debugger/designer/registry/DesignerRegistry.java`
- `ignition-module/gateway/src/main/java/dev/ignition/debugger/gateway/registry/GatewayRegistry.java`

## Discovery and Authentication

Startup behavior:

1. Designer module writes `designer-{pid}.json`.
2. Gateway module writes `gateway-{pid}.json`.
3. Extension reads configured registry directories.
4. User selects a target instance.
5. Extension authenticates with UUID secret from registry file.

Registry override environment/settings:

- `IGNITION_DEBUGGER_REGISTRY_DIR` (Designer side)
- `IGNITION_DEBUGGER_GATEWAY_REGISTRY_DIR` (Gateway side)
- `ignition-debugger.registryPath` (extension setting)
- `ignition-debugger.gatewayRegistryPath` (extension setting)

## Debug Execution Model

### Launch Mode

Typical flow:

1. `debug.startSession`
2. `debug.setBreakpoints`
3. `debug.run`
4. breakpoint/step events
5. `debug.stopSession`

Used for explicit script payload execution initiated by the debugger.

### Attach Mode

Typical flow:

1. `debug.attach`
2. `debug.setBreakpoints`
3. External trigger executes script in Ignition runtime
4. breakpoint/step events
5. `debug.detach`

Used to debug scripts executed by running Gateway/Designer contexts.

## Attach Tracing Internals

Attach mode uses global Jython trace installation in running gateway contexts.

Core pieces:

- `TracingThreadStateMapping` is injected into Jython app classloader.
- `Py.threadStateMapping` is replaced with `Unsafe`.
- JIT deoptimization is triggered so runtime starts reading updated mapping in compiled paths.
- Existing thread states are tagged with trace function.

Important runtime behavior:

- Re-attach must not redefine `TracingThreadStateMapping` if class already exists.
- Current implementation first tries `Class.forName(...)`, then defines class only if absent.

## Script Types Covered

Current validated paths:

- Launch-mode gateway library scripts
- Attach-mode WebDev scripts (`<<project/resource:func>>` co_filename)
- Attach-mode project library scripts (`<module:MODULE_PATH>` co_filename)

See [modes-and-breakpoints.md](modes-and-breakpoints.md) for filename matching details.

## Related Pages

- Protocol method details: [debug-protocol.md](debug-protocol.md)
- Ordered message flows: [sequence-diagrams.md](sequence-diagrams.md)
- Invariants and quick mapping: [reference.md](reference.md)
- Canonical machine context: [ai-context.md](ai-context.md)
