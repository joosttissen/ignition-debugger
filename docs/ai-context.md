# AI Context

This page is optimized for machine ingestion while remaining readable to humans.

## Canonical Identity

- Project: Ignition Debugger
- Repo type: VS Code extension + Ignition Java module
- Debug target language/runtime: Jython in Ignition 8.3 contexts

## Canonical Protocol Facts

- VS Code <-> Extension protocol: DAP
- Extension <-> Module protocol: JSON-RPC 2.0 over WebSocket
- Authentication: shared UUID secret from registry file

## Canonical Modes

### Launch mode

- Create session: `debug.startSession`
- Execute: `debug.run`
- End: `debug.stopSession`

### Attach mode

- Create session: `debug.attach`
- Trigger: external runtime execution
- End: `debug.detach`

## Canonical Filename Match Formats

- Filesystem path (suffix match)
- WebDev internal format: `<<project/resource:func>>`
- Library module format: `<module:MODULE_PATH>`

## Canonical Runtime Guarantees

- Breakpoint match requires file strategy match + exact line match.
- Attach re-entry must not duplicate-define `TracingThreadStateMapping` in same classloader.
- Attach tracing installs on existing and new thread states.

## Canonical Paths

- Extension code: `vscode-extension/src/`
- Debug engine: `ignition-module/common/src/main/java/dev/ignition/debugger/common/debug/`
- WebSocket server: `ignition-module/common/src/main/java/dev/ignition/debugger/common/server/`
- E2E tests: `e2e-test/src/`
- Test project: `ignition-data/projects/test-scripting/`

## Canonical Test Coverage

### Mock suites

- Launch mode
- Attach mode

### Docker suites

- Launch mode gateway script
- Attach mode WebDev script
- Attach mode library script

## Canonical Operations

- End-to-end build/test command: `bash build-and-test.sh`
- Module artifact: `ignition-module/build/ignition-debugger.unsigned.modl`
- Gateway health endpoint: `http://localhost:8088/StatusPing`
