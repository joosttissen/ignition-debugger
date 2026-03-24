# Ignition Debugger – End-to-End Test

Proves that **debugging a gateway script from VS Code works** by exercising the full
JSON-RPC debug-protocol flow using the same communication path as the VS Code extension.

---

## What the test does

The test simulates what VS Code does when you press **F5** to debug an Ignition gateway
script.  It runs in two modes:

### Mock mode (no Docker required)

A lightweight Node.js mock of the Ignition Gateway WebSocket debug server is started in
the same process.  The mock implements the full JSON-RPC 2.0 debug protocol that the Java
`DebugWebSocketServer` implements (same methods, same event notifications).

Steps exercised:

| Step | What is verified |
|------|-----------------|
| 1 | `ping` – connectivity check |
| 2 | `debug.startSession` – session is created with a non-empty `sessionId` |
| 3 | `debug.setBreakpoints` – breakpoint at line 17 is verified |
| 4 | `debug.run` – script execution is started |
| 5 | `debug.event.stopped` – `reason = "breakpoint"` event is received |
| 6 | `debug.getStackTrace` – frame name is `greet`, line is 17, path contains `code.py` |
| 7 | `debug.getScopes` – `Locals` scope with `variablesReference` is returned |
| 8 | `debug.getVariables` – local `name = "Ignition"` is visible |
| 9 | `debug.evaluate` – expression evaluation returns a result |
| 10 | `debug.continue` – execution is resumed |
| 11 | `debug.event.terminated` – script-completion event is received |
| 12 | `debug.stopSession` – session is cleaned up |

Run it:

```bash
cd e2e-test
npm install
npm test
```

### Docker mode (real Ignition gateway)

When the environment variable `IGNITION_GATEWAY_REGISTRY_FILE` is set, the test connects
to the real Ignition gateway running in Docker and exercises the exact same debug flow
against the actual Java `DebugWebSocketServer`.

```bash
cd e2e-test
IGNITION_GATEWAY_REGISTRY_FILE=../debugger-gateway-registry/gateway-<pid>.json npm test
```

Or use the orchestration script (see below).

---

## Test project: `test-scripting`

The test debugs this Python script, located at:

```
ignition-data/projects/test-scripting/ignition/script-python/gateway_scripts/code.py
```

The script is also loaded by Ignition as a project script library when the Docker gateway
starts (the `ignition-data/projects` directory is bind-mounted).

Interesting breakpoint lines:

| Line | Location | Good for inspecting |
|------|----------|---------------------|
| 17 | `message = …` inside `greet()` | `name` variable |
| 22 | `doubled = …` inside `calculate()` | `result` variable |
| 35 | `total = total + i` inside `run_all()` | `total`, `i` in a loop |

---

## Full Docker-based E2E test

### Prerequisites

1. **Build the Ignition module** (requires Java 11 and network access to the IA Nexus):

   ```bash
   cd ignition-module
   ./gradlew build
   ```

   The unsigned `.modl` file will be at `ignition-module/build/ignition-debugger-unsigned.modl`.

2. **Install the module** in the running gateway (once per container):
   - Open <http://localhost:8088/web/config/modules>
   - Click **Install or Upgrade a Module** → upload the `.modl` file
   - Accept the unsigned-module warning

### Run

```bash
cd e2e-test
bash run-e2e-test.sh
```

The script:
1. Starts the Ignition gateway via `docker compose up -d`
2. Waits for the gateway health endpoint
3. Waits for the gateway registry file (written by the module on startup)
4. Runs the Node.js E2E test against the real gateway
5. Stops Docker when done (pass `--keep-running` to leave it running)

### VS Code settings (needed for Docker mode)

Add to your `settings.json` (or the included workspace file):

```json
{
  "ignition-debugger.gatewayRegistryPath": "./debugger-gateway-registry"
}
```

---

## Directory structure

```
e2e-test/
├── package.json            Node.js package (ws, typescript)
├── tsconfig.json           TypeScript config
├── run-e2e-test.sh         Docker-based orchestration script
└── src/
    ├── gateway-mock.ts     Mock Ignition gateway WebSocket server
    ├── debug-client.ts     Standalone debug-protocol client (same protocol as the VS Code extension)
    └── e2e-test.ts         Main test – runs both mock and real-gateway modes
```
