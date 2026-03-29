# Reference

This page is the canonical quick-reference for API behavior, method mapping, and invariants.

## DAP to JSON-RPC Mapping

| DAP intent | JSON-RPC call(s) | Notes |
|---|---|---|
| initialize / connect | `authenticate`, `ping` | Auth must succeed before debug methods. |
| launch request | `debug.startSession` | Creates launch-mode session. |
| attach request | `debug.attach` | Creates attach-mode session. |
| disconnect (launch) | `debug.stopSession` | Ends launch-mode session. |
| disconnect (attach) | `debug.detach` | Ends attach-mode session. |
| setBreakpoints | `debug.setBreakpoints` | Breakpoints are verified server-side. |
| continue | `debug.continue` | Resumes paused execution. |
| next | `debug.stepOver` | Step over current line. |
| stepIn | `debug.stepInto` | Step into next call. |
| stepOut | `debug.stepOut` | Step out of current frame. |
| pause | `debug.pause` | Best-effort pause request. |
| stackTrace | `debug.getStackTrace` | Returns current frame list. |
| scopes | `debug.getScopes` | Returns scope handles for a frame. |
| variables | `debug.getVariables` | Expands a scope/variable reference. |
| evaluate | `debug.evaluate` | Expression evaluation in frame context. |

## Expected Events by Mode

### Launch mode

1. Optional `debug.event.output` while running.
2. `debug.event.stopped` on breakpoint/step.
3. `debug.event.terminated` when execution completes.
4. Optional `debug.event.exited` where process semantics are used.

### Attach mode

1. External trigger causes runtime execution.
2. Optional `debug.event.output` while running.
3. `debug.event.stopped` on breakpoint/step.
4. Additional trigger-driven execution may continue after `debug.continue`.
5. Session ends explicitly via `debug.detach`.

## Minimum Data Contracts

### `debug.event.stopped`

Required semantics:

- Must identify a pause reason (`breakpoint`, step-related reason, or pause).
- Must correspond to a debuggable paused frame context.

### `debug.getStackTrace`

Each frame should provide:

- Frame id
- Function/frame name
- File path (or internal filename)
- Line number

### `debug.getScopes`

Each scope should provide:

- Scope name
- `variablesReference` handle

### `debug.getVariables`

Each variable should provide:

- Name
- Value string
- Type (when available)
- Nested `variablesReference` (0 for leaf values)

## Breakpoint Match Invariants

A breakpoint is considered matched when both are true:

1. File match succeeds for at least one strategy.
2. Line number equals breakpoint line.

File strategies:

1. Suffix path match (`pathsSuffixMatch`)
2. Ignition internal WebDev match (`ignitionInternalMatch`)
3. Ignition module filename match (`ignitionModuleMatch`)

## Attach Internals Invariants

1. `TracingThreadStateMapping` must live in the Jython app classloader.
2. Re-attach must not redefine that class if already present.
3. Existing thread states must receive trace function installation.
4. JIT-compiled reads of `Py.threadStateMapping` must observe replacement after deopt/retransform.

## Regression Checklist

When touching protocol or runtime behavior:

1. Update [debug-protocol.md](debug-protocol.md).
2. Update [sequence-diagrams.md](sequence-diagrams.md).
3. Update E2E assertions in `e2e-test/src/e2e-test.ts`.
4. Run mock and Docker suites.
