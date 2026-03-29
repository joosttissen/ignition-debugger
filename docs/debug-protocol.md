# Debug Protocol

## Layers

1. VS Code <-> Extension: Debug Adapter Protocol (DAP)
2. Extension <-> Ignition Module: JSON-RPC 2.0 over WebSocket

The extension is the protocol translator between DAP and JSON-RPC.

## Connection Lifecycle

1. Discover target from registry JSON.
2. Open WebSocket to target port.
3. Send `authenticate` with shared secret.
4. On success, begin debugging calls.

## JSON-RPC Methods

### Session and control

- `authenticate`
- `ping`
- `debug.startSession`
- `debug.attach`
- `debug.detach`
- `debug.run`
- `debug.stopSession`

### Breakpoints and stepping

- `debug.setBreakpoints`
- `debug.continue`
- `debug.stepOver`
- `debug.stepInto`
- `debug.stepOut`
- `debug.pause`

### Introspection

- `debug.getStackTrace`
- `debug.getScopes`
- `debug.getVariables`
- `debug.evaluate`

## Event Notifications

Server pushes notifications (no `id`) for debugger state changes:

- `debug.event.stopped`
- `debug.event.output`
- `debug.event.terminated`
- `debug.event.exited`

## Canonical Flows

### Launch mode

1. `authenticate`
2. `debug.startSession`
3. `debug.setBreakpoints`
4. `debug.run`
5. `debug.event.stopped` (0..n)
6. stack/scopes/variables/evaluate as needed
7. `debug.continue` / step APIs until done
8. `debug.event.terminated`
9. `debug.stopSession`

### Attach mode

1. `authenticate`
2. `debug.attach`
3. `debug.setBreakpoints`
4. trigger script from Ignition runtime
5. `debug.event.stopped` (0..n)
6. stack/scopes/variables/evaluate as needed
7. `debug.continue` / step APIs until done
8. `debug.detach`

## DAP Mapping Notes

The extension maps DAP concepts to JSON-RPC methods roughly as follows:

- DAP initialize/launch/attach -> session/auth lifecycle calls
- DAP setBreakpoints -> `debug.setBreakpoints`
- DAP stackTrace/scopes/variables/evaluate -> matching `debug.get...` methods
- DAP continue/next/stepIn/stepOut/pause -> control methods

## Event Semantics

- `stopped` means script execution is paused at a breakpoint or stepping boundary.
- `terminated` means debugged execution context ended.
- `output` forwards script stdout/stderr or runtime diagnostic lines.
- `exited` is used when process-level completion with code is applicable.

## Contract Stability

For maintainers and AI agents:

- JSON method names are part of the wire contract; avoid silent renames.
- Keep response object shapes backward-compatible where possible.
- Update this document and E2E assertions when changing protocol contracts.

## Related Pages

- Ordered runtime flows: [sequence-diagrams.md](sequence-diagrams.md)
- Behavior and matching semantics: [modes-and-breakpoints.md](modes-and-breakpoints.md)
- API/invariant quick reference: [reference.md](reference.md)
- Shared terms: [glossary.md](glossary.md)
