# Development and Testing

## Prerequisites

- Docker + Docker Compose
- Java 11
- Node.js 18+
- VS Code (for extension debugging)

## Build Outputs

Ignition module artifact:

- `ignition-module/build/ignition-debugger.unsigned.modl`

## Recommended End-to-End Workflow

From repository root:

```bash
bash build-and-test.sh
```

What this script does:

1. Builds module.
2. Runs mock E2E.
3. Clears gateway module JAR cache in container.
4. Restarts gateway container.
5. Waits for health endpoint.
6. Waits for gateway registry file.
7. Runs Docker E2E suites.

## Manual Workflow

### Build module

```bash
cd ignition-module
./gradlew build
```

### Start gateway

```bash
docker compose up -d
```

### Run E2E tests

Mock mode:

```bash
cd e2e-test
npm install
npm test
```

Docker mode:

```bash
cd e2e-test
IGNITION_GATEWAY_REGISTRY_FILE=../debugger-gateway-registry/gateway-<pid>.json npm test
```

## Current E2E Suites

### Mock

- Launch-mode suite
- Attach-mode suite

### Docker

- Launch-mode gateway script suite
- Attach-mode WebDev suite
- Attach-mode library script suite

## Test Project Resources

- Library script under test:
  - `ignition-data/projects/test-scripting/ignition/script-python/gateway_scripts/code.py`
- WebDev trigger resource:
  - `ignition-data/projects/test-scripting/com.inductiveautomation.webdev/resources/test/doGet.py`

## Local Gateway Access

- URL: `http://localhost:8088`
- Default credentials: `admin` / `password`

## Registry Paths

Host paths used by this repository setup:

- Designer: `./debugger-registry`
- Gateway: `./debugger-gateway-registry`

VS Code settings to align discovery:

- `ignition-debugger.registryPath`
- `ignition-debugger.gatewayRegistryPath`

## Change Checklist (for maintainers and AI agents)

When changing debugger behavior, verify all of the following:

1. Module builds successfully.
2. E2E mock mode passes.
3. E2E Docker mode passes all suites.
4. Docs in `README.md`, `e2e-test/README.md`, and `docs/` are updated.
5. Any protocol or event-shape changes are reflected in `docs/debug-protocol.md`.

## Related Pages

- System architecture: [architecture.md](architecture.md)
- Debug contract details: [debug-protocol.md](debug-protocol.md)
- Failure diagnosis playbook: [troubleshooting.md](troubleshooting.md)
