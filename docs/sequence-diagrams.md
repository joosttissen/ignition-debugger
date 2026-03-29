# Sequence Diagrams

This page provides machine-friendly and human-friendly sequence views of the debugger flows.

Conventions:

- `N:` prefix means ordered step number.
- `[...]` indicates state transitions.
- JSON-RPC method names are shown exactly.

## Actors

- User: Developer in VS Code UI
- VS Code: Debugger UI + DAP runtime
- Extension: Ignition debug adapter and connection services
- Module: Ignition module WebSocket server and debug engine
- Runtime: Jython execution environment inside Ignition

## Launch Mode Sequence

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant VS as VS Code (DAP)
    participant Ext as Extension (DAP<->JSON-RPC)
    participant Mod as Ignition Module (JSON-RPC)
    participant Rt as Jython Runtime

    User->>VS: Start debug configuration
    VS->>Ext: initialize + launch
    Ext->>Mod: authenticate(secret)
    Mod-->>Ext: ok

    Ext->>Mod: debug.startSession(scriptCode, filePath)
    Mod-->>Ext: sessionId

    Ext->>Mod: debug.setBreakpoints(sessionId, filePath, lines)
    Mod-->>Ext: verified breakpoints

    Ext->>Mod: debug.run(sessionId)
    Mod->>Rt: compile/execute script

    Rt-->>Mod: trace pause at breakpoint/step
    Mod-->>Ext: debug.event.stopped(reason=breakpoint)
    Ext-->>VS: DAP stopped event

    VS->>Ext: stackTrace/scopes/variables/evaluate
    Ext->>Mod: debug.getStackTrace/getScopes/getVariables/debug.evaluate
    Mod-->>Ext: data
    Ext-->>VS: DAP responses

    VS->>Ext: continue / next / stepIn / stepOut
    Ext->>Mod: debug.continue / debug.stepOver / debug.stepInto / debug.stepOut

    Rt-->>Mod: execution complete
    Mod-->>Ext: debug.event.terminated
    Ext-->>VS: DAP terminated event

    Ext->>Mod: debug.stopSession(sessionId)
    Mod-->>Ext: ok
```

### Launch Mode State Model

1. `Disconnected`
2. `ConnectedUnauthenticated`
3. `Authenticated`
4. `SessionCreated`
5. `Running`
6. `Paused`
7. `Terminated`
8. `SessionStopped`

## Attach Mode Sequence

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant VS as VS Code (DAP)
    participant Ext as Extension (DAP<->JSON-RPC)
    participant Mod as Ignition Module (JSON-RPC)
    participant Rt as Jython Runtime
    participant Trig as External Trigger (WebDev/Perspective/Event)

    User->>VS: Attach debug configuration
    VS->>Ext: initialize + attach
    Ext->>Mod: authenticate(secret)
    Mod-->>Ext: ok

    Ext->>Mod: debug.attach(projectName?)
    Mod->>Rt: install global trace hooks
    Mod-->>Ext: attachSessionId

    Ext->>Mod: debug.setBreakpoints(attachSessionId, filePath, lines)
    Mod-->>Ext: verified breakpoints

    Trig->>Rt: Execute script via runtime context
    Rt-->>Mod: trace pause at breakpoint/step
    Mod-->>Ext: debug.event.stopped(reason=breakpoint)
    Ext-->>VS: DAP stopped event

    VS->>Ext: stackTrace/scopes/variables/evaluate
    Ext->>Mod: debug.getStackTrace/getScopes/getVariables/debug.evaluate
    Mod-->>Ext: data
    Ext-->>VS: DAP responses

    VS->>Ext: continue / next / stepIn / stepOut
    Ext->>Mod: debug.continue / debug.stepOver / debug.stepInto / debug.stepOut

    Rt-->>Trig: script response/output complete
    Ext->>Mod: debug.detach(attachSessionId)
    Mod->>Rt: remove/disable attach tracing for session
    Mod-->>Ext: ok
```

### Attach Mode State Model

1. `Disconnected`
2. `ConnectedUnauthenticated`
3. `Authenticated`
4. `Attached`
5. `WaitingForTrigger`
6. `Paused`
7. `Resumed`
8. `Detached`

## Breakpoint Filename Resolution Sequence

This shows how module-side matching resolves runtime filenames to user breakpoint file paths.

```mermaid
sequenceDiagram
    autonumber
    participant Rt as Runtime Frame (co_filename)
    participant BP as BreakpointManager
    participant Store as Breakpoint Store (byFile)

    Rt->>BP: isBreakpoint(co_filename, line)
    BP->>Store: exact lookup by filePath
    alt Exact hit
        Store-->>BP: breakpoint list
        BP-->>Rt: true/false by line
    else Exact miss
        BP->>Store: iterate entries
        BP->>BP: pathsSuffixMatch(a, b)
        BP->>BP: ignitionInternalMatch(a, b)
        BP->>BP: ignitionModuleMatch(a, b)
        BP-->>Rt: true if any matcher + line matches
    end
```

## Filename Formats and Matchers

1. Filesystem path
   - Example: `/.../resources/test/doGet.py`
   - Matcher: `pathsSuffixMatch`

2. WebDev internal function format
   - Example: `<<test-scripting/test:doGet>>`
   - Matcher: `ignitionInternalMatch`

3. Project library module format
   - Example: `<module:gateway_scripts>`
   - Matcher: `ignitionModuleMatch`

## AI Parsing Notes

To parse flow programmatically:

1. Use diagram `autonumber` as canonical event order.
2. Treat JSON-RPC method names as stable API identifiers.
3. Infer mode by presence of `debug.startSession` (launch) or `debug.attach` (attach).
4. Infer pause points by `debug.event.stopped` notifications.
5. Infer end-of-execution by `debug.event.terminated` (launch) or `debug.detach` completion (attach lifecycle).

## Compact Operation Matrix

This matrix provides a one-glance view of client action -> JSON-RPC call -> expected primary outcome.

| Client intent | JSON-RPC method | Primary expected outcome |
|---|---|---|
| Authenticate | `authenticate` | Auth success/failure result |
| Health check | `ping` | Status response |
| Launch session | `debug.startSession` | Launch session id |
| Attach session | `debug.attach` | Attach session id + tracing active |
| Set breakpoints | `debug.setBreakpoints` | Verified breakpoint list |
| Start launch execution | `debug.run` | Runtime begins script execution |
| Continue | `debug.continue` | Resume paused execution |
| Step over | `debug.stepOver` | Pause at next boundary |
| Step into | `debug.stepInto` | Pause in called frame boundary |
| Step out | `debug.stepOut` | Pause in caller boundary |
| Pause request | `debug.pause` | Best-effort stop transition |
| Read stack | `debug.getStackTrace` | Frame list |
| Read scopes | `debug.getScopes` | Scope list + references |
| Read variables | `debug.getVariables` | Variable list |
| Evaluate | `debug.evaluate` | Expression result string/value |
| End launch session | `debug.stopSession` | Launch session cleanup |
| End attach session | `debug.detach` | Attach session cleanup |

## Cross-References

- Protocol contract details: [debug-protocol.md](debug-protocol.md)
- Behavioral invariants: [reference.md](reference.md)
