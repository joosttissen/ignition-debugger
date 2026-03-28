# Modes and Breakpoint Semantics

## Overview

The debugger supports two execution modes:

1. Launch mode: debugger starts script execution.
2. Attach mode: debugger hooks into script execution started by Ignition runtime contexts.

## Launch Mode

Typical usage:

- Debug a script file directly with `startSession` + `run`.

Characteristics:

- Script source/path are explicitly supplied by debug client.
- File path mapping is usually straightforward.

## Attach Mode

Typical usage:

- Attach to running gateway/designer context.
- Set breakpoints.
- Trigger script through WebDev, Perspective, events, timers, etc.

Characteristics:

- Jython `co_filename` can differ from filesystem path.
- Matching logic must reconcile internal filename formats.

## Breakpoint Filename Matching

The current `BreakpointManager` behavior supports three patterns.

### 1. Filesystem path suffix matching

Purpose:

- Handle host/container path differences.

Example:

- Breakpoint path: `/workspaces/.../resources/test/doGet.py`
- Runtime path: `/usr/local/bin/ignition/data/projects/.../resources/test/doGet.py`

Rule:

- Match if normalized longer path ends with shorter path.

### 2. Ignition WebDev internal function format

Format:

- `<<project/resource:func>>`

Example:

- Runtime frame filename: `<<test-scripting/test:doGet>>`
- Breakpoint file path contains project/resource/function parts.

### 3. Ignition project library module format

Format:

- `<module:MODULE_PATH>`

Example:

- Runtime frame filename: `<module:gateway_scripts>`
- Breakpoint file path: `.../ignition/script-python/gateway_scripts/code.py`

Sub-package behavior:

- Dots in module path represent nested package directories.
- Example: `<module:gateway_scripts.utils>` matches path segment `gateway_scripts/utils`.

## Attach Validation Coverage

Current E2E validation covers:

1. Attach to WebDev endpoint script (`doGet.py`) and stop in `build_greeting`.
2. Attach to library script executed from WebDev (`gateway_scripts.greet`) and stop in `greet`.

Observed runtime frame example for library script:

- `file='<module:gateway_scripts>'`

## What This Means for Perspective and Other Gateway Contexts

If a gateway context executes project library code and Jython reports `<module:...>`
filename format, the same matching logic applies. The behavior is execution-context agnostic
at the filename-matching layer.
