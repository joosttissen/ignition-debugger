# Ignition Debugger Documentation

This documentation set is written for both:

- Humans who want to understand, run, and extend the project.
- AI agents that need precise, structured project context.

## Start Here

- System overview: [architecture.md](architecture.md)
- Message-order and state transitions: [sequence-diagrams.md](sequence-diagrams.md)
- Wire protocol and message flow: [debug-protocol.md](debug-protocol.md)
- API/method invariants quick reference: [reference.md](reference.md)
- Debug modes, script types, and breakpoint matching: [modes-and-breakpoints.md](modes-and-breakpoints.md)
- Build, run, and test operations: [development-and-testing.md](development-and-testing.md)
- Failure diagnosis and common fixes: [troubleshooting.md](troubleshooting.md)
- Terminology index: [glossary.md](glossary.md)
- Machine-oriented canonical context: [ai-context.md](ai-context.md)

## Quick Facts

- Project type: VS Code debugger extension + Ignition module
- Runtime protocol: JSON-RPC 2.0 over WebSocket
- Debugger UX protocol: DAP (inside VS Code extension)
- Primary debug scopes: Designer and Gateway
- Modes:
  - Launch mode (`debug.startSession` + `debug.run`)
  - Attach mode (`debug.attach` + `debug.detach`)

## Source Map

Core areas in the repository:

- Extension: `vscode-extension/src/`
- Module common debug engine: `ignition-module/common/src/main/java/dev/ignition/debugger/common/`
- Designer hook: `ignition-module/designer/src/main/java/dev/ignition/debugger/designer/`
- Gateway hook: `ignition-module/gateway/src/main/java/dev/ignition/debugger/gateway/`
- End-to-end tests: `e2e-test/src/`
- Test project resources: `ignition-data/projects/test-scripting/`

## Documentation Design Notes

These docs are intentionally:

- High-signal and explicit about behavior.
- Indexed and cross-linked for fast navigation.
- Friendly to machine parsing (clear headings, stable terminology, direct file pointers).
