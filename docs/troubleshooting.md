# Troubleshooting

## Quick Triage

1. Confirm gateway is running:

```bash
curl -s http://localhost:8088/StatusPing
```

2. Confirm gateway registry file exists:

- `debugger-gateway-registry/gateway-*.json`

3. Confirm extension points to correct registry settings.

4. Run full validation:

```bash
bash build-and-test.sh
```

## Breakpoint Not Hit in Attach Mode

Possible causes:

1. Script did not execute on the attached runtime path.
2. Breakpoint filename did not match runtime `co_filename` format.
3. Attach session is connected but no trigger occurred.

Actions:

1. Trigger script execution explicitly (WebDev endpoint/event/timer action).
2. Inspect runtime frame filename from logs and compare to supported formats:
   - filesystem path
   - `<<project/resource:func>>`
   - `<module:MODULE_PATH>`
3. Ensure breakpoint file path points to the actual project resource file.

## Re-Attach Errors (duplicate class definition)

Symptom:

- `LinkageError` when attaching multiple times due to duplicate definition of
  `org.python.core.TracingThreadStateMapping`.

Current expected behavior:

- `GlobalTraceInstaller` tries loading class first and only defines if missing.

If this regresses:

1. Re-check `GlobalTraceInstaller` classloading logic.
2. Re-run Docker attach suites to confirm repeated attach sessions work.

## Docker Tests Fail but Mock Tests Pass

Likely causes:

1. Module not installed/upgraded in gateway.
2. Stale gateway JAR cache.
3. Registry file points to stale secret/port.

Actions:

1. Use `build-and-test.sh` to force full cycle.
2. Ensure cache clear step runs (`ignition-data/jar-cache/dev.ignition.debugger/`).
3. Verify newest `gateway-*.json` secret is used by test command.

## WebDev Endpoint Issues

Symptom examples:

- 404 or timeout on `/system/webdev/test-scripting/test`
- Response missing expected fields

Actions:

1. Confirm WebDev module is installed and enabled in Ignition.
2. Confirm test project is present under `ignition-data/projects/test-scripting/`.
3. Confirm endpoint script path:
   - `ignition-data/projects/test-scripting/com.inductiveautomation.webdev/resources/test/doGet.py`

## Artifact Name Mismatch

Expected module file name:

- `ignition-debugger.unsigned.modl`

If docs/scripts reference a different name, align them to the value above.

## Log Inspection Tips

Useful runtime logs:

- Trace call events emitted by `TraceFunctionAdapter`.
- Attach session lifecycle logs from WebSocket/debug server classes.

Container log command:

```bash
docker logs ignition-gateway --tail 200
```
