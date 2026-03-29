# Glossary

## Core Terms

### DAP

Debug Adapter Protocol used between VS Code and the extension.

### JSON-RPC

Wire protocol used between extension and Ignition module.

### Launch mode

Debugger starts execution from a script payload (`debug.startSession` + `debug.run`).

### Attach mode

Debugger attaches to already-running runtime contexts (`debug.attach` + external trigger).

### Runtime trigger

An external action that causes script execution in attach mode (WebDev call, Perspective action, event script, timer script).

### `co_filename`

Jython frame filename field used by debugger matching logic.

### WebDev internal filename format

`<<project/resource:func>>` filename shape for compiled endpoint functions.

### Module internal filename format

`<module:MODULE_PATH>` filename shape for project library modules.

### Breakpoint verification

Server-side acceptance process returning breakpoint metadata from `debug.setBreakpoints`.

### Registry file

Discovery JSON written by Designer or Gateway module with connection metadata and shared secret.

### JAR cache clear

Operational step to remove stale extracted module jars so gateway loads current module build.

## Runtime Components

### `JythonDebugger`

Core debugger logic for pause/step/inspect behavior.

### `BreakpointManager`

Stores breakpoints and resolves file/line matches against runtime frames.

### `GlobalTraceInstaller`

Attach-mode installer for global tracing in running gateway contexts.

### `TracingThreadStateMapping`

Injected Jython class used to install per-thread trace hooks.

### `DebugWebSocketServer`

JSON-RPC server endpoint inside module runtime.
