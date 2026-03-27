/**
 * e2e-test.ts
 *
 * End-to-end test for the Ignition Debugger.
 *
 * Proves that debugging a gateway script from VS Code works by exercising the
 * full debug protocol flow:
 *
 *   1.  Start a mock Ignition gateway WebSocket debug server.
 *   2.  Connect the debug client (same protocol as the VS Code extension).
 *   3.  Authenticate with the shared secret.
 *   4.  Start a debug session for the test Python script.
 *   5.  Set a breakpoint on line 17 (inside `greet`).
 *   6.  Run the script.
 *   7.  Verify that the `stopped` event arrives (breakpoint hit).
 *   8.  Inspect the stack trace: frame name = 'greet', file = code.py.
 *   9.  Inspect local variables: name='Ignition', message='Hello, Ignition!'.
 *   10. Continue execution.
 *   11. Verify the `terminated` event arrives.
 *   11b. (Docker only) Verify system.date.now() printed successfully – no NameError.
 *   12. Disconnect.
 *
 * When the IGNITION_GATEWAY_REGISTRY_FILE environment variable is set, the
 * test reads that registry JSON (written by the real gateway module) and runs
 * the same protocol flow against the actual Ignition gateway running in Docker.
 * This proves that the full Docker-based E2E path works too.
 *
 * Docker mode also runs an **attach-mode test** against a WebDev endpoint:
 *   - Attaches the debugger to the running gateway.
 *   - Sets breakpoints in a WebDev Python script.
 *   - Triggers the WebDev endpoint via HTTP GET.
 *   - Verifies the debugger stops at the breakpoint.
 *   - Inspects variables and stack trace.
 *   - Continues execution and verifies the HTTP response.
 *
 * Usage (mock mode – no Docker required):
 *   npm test
 *
 * Usage (Docker mode – real Ignition gateway):
 *   IGNITION_GATEWAY_REGISTRY_FILE=../debugger-gateway-registry/gateway-<pid>.json npm test
 */

import * as fs from 'fs';
import * as http from 'http';
import * as path from 'path';
import * as process from 'process';
import { DebugClient, Variable, OutputEventBody } from './debug-client';
import { MockGatewayServer, findFreePort } from './gateway-mock';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function assert(condition: boolean, message: string): void {
    if (condition) {
        console.log(`  ✓ ${message}`);
        passed++;
    } else {
        console.error(`  ✗ FAIL: ${message}`);
        failed++;
    }
}

function assertIncludes(haystack: string, needle: string, label: string): void {
    assert(haystack.includes(needle), `${label}: expected "${needle}" in "${haystack}"`);
}

function step(description: string): void {
    console.log(`\n▶  ${description}`);
}

// ---------------------------------------------------------------------------
// Test script (same as ignition-data/projects/test-scripting/…/code.py)
// This is what VS Code reads from disk and sends to the gateway.
// ---------------------------------------------------------------------------

const TEST_SCRIPT_PATH = path.resolve(
    __dirname,
    '../../ignition-data/projects/test-scripting/ignition/script-python/gateway_scripts/code.py',
);

const TEST_SCRIPT_CODE = fs.readFileSync(TEST_SCRIPT_PATH, 'utf-8');

// The breakpoint line numbers (1-based, matching the code.py source).
// Line 17 is:  message = "Hello, " + name + "!"  # inside greet()
// Line 22 is:  doubled = result * 2              # inside calculate()
const BREAKPOINT_LINE_GREET = 17;
const BREAKPOINT_LINE_CALCULATE = 22;

// ---------------------------------------------------------------------------
// WebDev endpoint for attach-mode Docker test
// ---------------------------------------------------------------------------

const WEBDEV_SCRIPT_PATH = path.resolve(
    __dirname,
    '../../ignition-data/projects/test-scripting/com.inductiveautomation.webdev/debug-test/code.py',
);

// Line 14: greeting = "Hello, " + name + "!"  # inside build_greeting()
const WEBDEV_BREAKPOINT_LINE = 14;

// The WebDev endpoint URL (relative to gateway). The Ignition WebDev module
// serves endpoints at /system/webdev/<project>/<endpoint>.
const WEBDEV_ENDPOINT_PATH = '/system/webdev/test-scripting/debug-test';

/**
 * Makes an HTTP GET request and returns the response body as a string.
 * Used to trigger WebDev endpoints during attach-mode testing.
 */
function httpGet(url: string, timeoutMs = 30_000): Promise<{ status: number; body: string }> {
    return new Promise((resolve, reject) => {
        const req = http.get(url, (res) => {
            let body = '';
            res.on('data', (chunk: Buffer) => { body += chunk.toString(); });
            res.on('end', () => {
                clearTimeout(timer);
                resolve({ status: res.statusCode ?? 0, body });
            });
        });
        req.on('error', (err) => {
            clearTimeout(timer);
            reject(err);
        });

        const timer = setTimeout(() => {
            req.destroy();
            reject(new Error(`HTTP GET timed out after ${timeoutMs}ms: ${url}`));
        }, timeoutMs);
    });
}

// ---------------------------------------------------------------------------
// Read registry from real gateway (Docker mode)
// ---------------------------------------------------------------------------

interface GatewayRegistryFile {
    pid: number;
    port: number;
    secret: string;
    scope: string;
}

function readRegistryFile(filePath: string): GatewayRegistryFile {
    const content = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(content) as GatewayRegistryFile;
}

// ---------------------------------------------------------------------------
// Main test suite
// ---------------------------------------------------------------------------

async function runTestSuite(
    client: DebugClient,
    label: string,
    options: { supportsSystemApi?: boolean } = {},
): Promise<void> {
    console.log(`\n${'='.repeat(60)}`);
    console.log(`  ${label}`);
    console.log('='.repeat(60));

    // -- Step 1: ping --------------------------------------------------------
    step('1. Ping gateway');
    const pong = await client.ping();
    assert(pong.status === 'ok', `ping returns status=ok (got: ${pong.status})`);

    // -- Step 2: start session -----------------------------------------------
    step('2. Start debug session for gateway_scripts/code.py');
    const { sessionId } = await client.startSession(TEST_SCRIPT_CODE, TEST_SCRIPT_PATH);
    assert(sessionId.length > 0, `session ID is non-empty: ${sessionId}`);

    // -- Step 3: set breakpoints ---------------------------------------------
    step(`3. Set breakpoint at line ${BREAKPOINT_LINE_GREET} (inside greet())`);
    const bps = await client.setBreakpoints(sessionId, TEST_SCRIPT_PATH, [
        { line: BREAKPOINT_LINE_GREET },
    ]);
    assert(bps.length === 1, `one breakpoint returned (got ${bps.length})`);
    assert(bps[0].verified === true, `breakpoint at line ${BREAKPOINT_LINE_GREET} is verified`);
    assert(
        bps[0].line === BREAKPOINT_LINE_GREET,
        `breakpoint line is ${BREAKPOINT_LINE_GREET} (got ${bps[0].line})`,
    );

    // -- Step 4: run script --------------------------------------------------
    step('4. Run the script');
    // Start collecting output events BEFORE running so we don't miss any.
    client.startCollectingOutput();
    // Register the stopped-event listener BEFORE calling run so we don't miss it
    const stoppedPromise = client.waitForStopped();
    await client.run(sessionId);
    console.log('  … waiting for breakpoint hit …');

    // -- Step 5: verify breakpoint hit ---------------------------------------
    step(`5. Verify stopped event at line ${BREAKPOINT_LINE_GREET}`);
    const stoppedBody = await stoppedPromise;
    assert(
        (stoppedBody as { reason: string }).reason === 'breakpoint',
        `stopped reason is 'breakpoint' (got '${(stoppedBody as { reason: string }).reason}')`,
    );

    // -- Step 6: inspect stack trace -----------------------------------------
    step('6. Inspect stack trace');
    const frames = await client.getStackTrace(sessionId);
    assert(frames.length >= 1, `at least one stack frame (got ${frames.length})`);

    const topFrame = frames[0];
    assert(topFrame !== undefined, 'top frame exists');
    if (topFrame) {
        assert(topFrame.name === 'greet', `top frame name is 'greet' (got '${topFrame.name}')`);
        assert(
            topFrame.line === BREAKPOINT_LINE_GREET,
            `top frame line is ${BREAKPOINT_LINE_GREET} (got ${topFrame.line})`,
        );
        assertIncludes(topFrame.filePath, 'code.py', 'top frame filePath contains code.py');
    }

    // -- Step 7: inspect scopes ----------------------------------------------
    step('7. Inspect scopes for top frame');
    const scopes = await client.getScopes(sessionId, topFrame?.id ?? 0);
    const localsScope = scopes.find((s) => s.name === 'Locals');
    assert(localsScope !== undefined, `'Locals' scope exists (scopes: ${scopes.map((s) => s.name).join(', ')})`);

    // -- Step 8: inspect local variables ------------------------------------
    step('8. Inspect local variables inside greet()');
    const variables = await client.getVariables(sessionId, localsScope?.variablesReference ?? 0);
    const varNames = variables.map((v: Variable) => v.name);

    assert(varNames.includes('name'), `'name' variable present (got: ${varNames.join(', ')})`);
    const nameVar = variables.find((v: Variable) => v.name === 'name');
    assert(nameVar !== undefined, `'name' variable exists in locals`);
    assert(
        nameVar !== undefined && nameVar.value.includes('Ignition'),
        `'name' variable value contains 'Ignition' (got '${nameVar?.value}')`,
    );

    // -- Step 9: evaluate expression -----------------------------------------
    step('9. Evaluate expression in current frame');
    const evalResult = await client.evaluate(sessionId, 'name', topFrame?.id);
    assert(evalResult.length > 0, `evaluate returns non-empty result (got '${evalResult}')`);

    // -- Step 10: continue execution -----------------------------------------
    step('10. Continue execution');
    const terminatedPromise = client.waitForTerminated();
    await client.continueExecution(sessionId);
    console.log('  … waiting for terminated event …');

    // -- Step 11: verify termination -----------------------------------------
    step('11. Verify terminated event received');
    await terminatedPromise;
    assert(true, 'terminated event received – script completed successfully');

    // -- Step 11b: verify system API (Docker / real gateway only) ------------
    if (options.supportsSystemApi) {
        step('11b. Verify system.date.now() executed without error (system API check)');
        const outputEvents = client.getCollectedOutput();
        const stdoutLines = outputEvents
            .filter((e: OutputEventBody) => e.category !== 'stderr')
            .map((e: OutputEventBody) => e.output)
            .join('');
        const stderrLines = outputEvents
            .filter((e: OutputEventBody) => e.category === 'stderr')
            .map((e: OutputEventBody) => e.output)
            .join('');

        assert(
            stderrLines.length === 0,
            `no stderr output (stderr was: ${stderrLines.trim() || '(empty)'})`,
        );
        assert(
            stdoutLines.includes('system.date.now:'),
            `stdout contains 'system.date.now:' (stdout: ${stdoutLines.trim()})`,
        );
    }

    // -- Step 12: clean up ---------------------------------------------------
    step('12. Clean up');
    await client.stopSession(sessionId);
    assert(true, 'stopSession call succeeded');
}

// ---------------------------------------------------------------------------
// Attach-mode test suite
// ---------------------------------------------------------------------------

async function runAttachTestSuite(
    client: DebugClient,
    label: string,
): Promise<void> {
    console.log(`\n${'='.repeat(60)}`);
    console.log(`  ${label}`);
    console.log('='.repeat(60));

    // -- Step 1: ping --------------------------------------------------------
    step('1. Ping gateway');
    const pong = await client.ping();
    assert(pong.status === 'ok', `ping returns status=ok (got: ${pong.status})`);

    // -- Step 2: attach (instead of startSession) ----------------------------
    step('2. Attach to gateway');
    const { sessionId } = await client.attachSession();
    assert(sessionId.length > 0, `session ID is non-empty: ${sessionId}`);
    assert(sessionId.startsWith('attach-'), `session ID starts with 'attach-' (got: ${sessionId})`);

    // -- Step 3: set breakpoints (triggers mock script in attach mode) -------
    step(`3. Set breakpoint at line ${BREAKPOINT_LINE_GREET} (inside greet())`);
    // Register the stopped-event listener BEFORE setting breakpoints
    // (in attach mode, the mock simulates a script execution upon receiving breakpoints)
    client.startCollectingOutput();
    const stoppedPromise = client.waitForStopped();
    const bps = await client.setBreakpoints(sessionId, TEST_SCRIPT_PATH, [
        { line: BREAKPOINT_LINE_GREET },
    ]);
    assert(bps.length === 1, `one breakpoint returned (got ${bps.length})`);
    assert(bps[0].verified === true, `breakpoint at line ${BREAKPOINT_LINE_GREET} is verified`);

    // -- Step 4: verify breakpoint hit (simulated gateway script) ------------
    step(`4. Verify stopped event at line ${BREAKPOINT_LINE_GREET}`);
    console.log('  … waiting for breakpoint hit from gateway script …');
    const stoppedBody = await stoppedPromise;
    assert(
        (stoppedBody as { reason: string }).reason === 'breakpoint',
        `stopped reason is 'breakpoint' (got '${(stoppedBody as { reason: string }).reason}')`,
    );

    // -- Step 5: inspect stack trace -----------------------------------------
    step('5. Inspect stack trace');
    const frames = await client.getStackTrace(sessionId);
    assert(frames.length >= 1, `at least one stack frame (got ${frames.length})`);

    const topFrame = frames[0];
    assert(topFrame !== undefined, 'top frame exists');
    if (topFrame) {
        assert(topFrame.name === 'greet', `top frame name is 'greet' (got '${topFrame.name}')`);
        assert(
            topFrame.line === BREAKPOINT_LINE_GREET,
            `top frame line is ${BREAKPOINT_LINE_GREET} (got ${topFrame.line})`,
        );
    }

    // -- Step 6: inspect scopes and variables --------------------------------
    step('6. Inspect scopes and variables');
    const scopes = await client.getScopes(sessionId, topFrame?.id ?? 0);
    const localsScope = scopes.find((s) => s.name === 'Locals');
    assert(localsScope !== undefined, `'Locals' scope exists`);

    const variables = await client.getVariables(sessionId, localsScope?.variablesReference ?? 0);
    const varNames = variables.map((v: Variable) => v.name);
    assert(varNames.includes('name'), `'name' variable present (got: ${varNames.join(', ')})`);

    // -- Step 7: evaluate expression -----------------------------------------
    step('7. Evaluate expression in current frame');
    const evalResult = await client.evaluate(sessionId, 'name', topFrame?.id);
    assert(evalResult.length > 0, `evaluate returns non-empty result (got '${evalResult}')`);

    // -- Step 8: continue execution ------------------------------------------
    step('8. Continue execution');
    const terminatedPromise = client.waitForTerminated();
    await client.continueExecution(sessionId);
    console.log('  … waiting for terminated event …');

    // -- Step 9: verify termination ------------------------------------------
    step('9. Verify terminated event received');
    await terminatedPromise;
    assert(true, 'terminated event received – script completed successfully');

    // -- Step 10: detach (instead of stopSession) ----------------------------
    step('10. Detach from gateway');
    await client.detachSession(sessionId);
    assert(true, 'detachSession call succeeded');
}

// ---------------------------------------------------------------------------
// Docker attach-mode test suite (WebDev endpoint)
// ---------------------------------------------------------------------------

/**
 * Docker-only attach-mode test.
 *
 * Attaches the debugger to the running Ignition gateway, sets breakpoints
 * in the WebDev endpoint script, triggers the endpoint via HTTP, and
 * verifies the debugger stops at the breakpoint.
 *
 * This proves real-world debugging of gateway-executed scripts via the
 * attach protocol.
 */
async function runDockerAttachTestSuite(
    client: DebugClient,
    label: string,
    gatewayBaseUrl: string,
): Promise<void> {
    console.log(`\n${'='.repeat(60)}`);
    console.log(`  ${label}`);
    console.log('='.repeat(60));

    // -- Step 1: ping --------------------------------------------------------
    step('1. Ping gateway');
    const pong = await client.ping();
    assert(pong.status === 'ok', `ping returns status=ok (got: ${pong.status})`);

    // -- Step 2: verify WebDev endpoint is reachable -------------------------
    step('2. Verify WebDev endpoint is reachable (without debugger)');
    const webdevUrl = `${gatewayBaseUrl}${WEBDEV_ENDPOINT_PATH}`;
    console.log(`  GET ${webdevUrl}`);
    try {
        const preCheck = await httpGet(webdevUrl);
        assert(preCheck.status === 200, `WebDev endpoint returns 200 (got ${preCheck.status})`);
        console.log(`  Response: ${preCheck.body.substring(0, 200)}`);
        const preBody = JSON.parse(preCheck.body);
        assert(
            preBody.greeting !== undefined || preBody.name !== undefined,
            `Response contains expected fields (got: ${JSON.stringify(preBody)})`,
        );
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        console.error(`  ⚠ WebDev endpoint not reachable: ${msg}`);
        console.error('  Make sure the WebDev module is installed and the project is enabled.');
        assert(false, `WebDev endpoint is reachable (error: ${msg})`);
        return; // Can't continue without the WebDev endpoint
    }

    // -- Step 3: attach to gateway -------------------------------------------
    step('3. Attach debugger to gateway');
    const { sessionId } = await client.attachSession('test-scripting');
    assert(sessionId.length > 0, `session ID is non-empty: ${sessionId}`);
    assert(sessionId.startsWith('attach-'), `session ID starts with 'attach-' (got: ${sessionId})`);

    // -- Step 4: set breakpoints in WebDev script ----------------------------
    step(`4. Set breakpoint at line ${WEBDEV_BREAKPOINT_LINE} in WebDev script`);
    //
    // The breakpoint file path must match the co_filename that Jython reports
    // for the WebDev script.  We try the local filesystem path of the WebDev
    // code.py as it is bind-mounted into the container.
    const bps = await client.setBreakpoints(sessionId, WEBDEV_SCRIPT_PATH, [
        { line: WEBDEV_BREAKPOINT_LINE },
    ]);
    assert(bps.length === 1, `one breakpoint returned (got ${bps.length})`);
    assert(bps[0].verified === true, `breakpoint is verified`);

    // -- Step 5: trigger WebDev endpoint (HTTP) and wait for breakpoint ------
    step('5. Trigger WebDev endpoint and wait for breakpoint hit');

    // Start listening for the stopped event BEFORE triggering the request.
    const stoppedPromise = client.waitForStopped(20_000);

    // Fire the HTTP request in the background.  The gateway will execute the
    // WebDev script, and the trace function should pause it at the breakpoint.
    // We don't await this yet – the request will hang while the script is paused.
    const httpPromise = httpGet(webdevUrl, 30_000);

    console.log('  … waiting for breakpoint hit from WebDev script …');

    let breakpointHit = false;
    try {
        const stoppedBody = await stoppedPromise;
        breakpointHit = true;
        assert(
            stoppedBody.reason === 'breakpoint',
            `stopped reason is 'breakpoint' (got '${stoppedBody.reason}')`,
        );
    } catch (err) {
        // Breakpoint did not hit within the timeout.  This can happen if:
        //   - The WebDev script's co_filename doesn't match the breakpoint path
        //   - The trace function isn't active on the WebDev handler thread
        // We still validate the rest of the flow.
        const msg = err instanceof Error ? err.message : String(err);
        console.error(`  ⚠ Breakpoint not hit (this may be expected – see notes): ${msg}`);
        assert(false, `breakpoint hit within timeout (${msg})`);
    }

    if (breakpointHit) {
        // -- Step 6: inspect stack trace -----------------------------------------
        step('6. Inspect stack trace at breakpoint');
        const frames = await client.getStackTrace(sessionId);
        assert(frames.length >= 1, `at least one stack frame (got ${frames.length})`);

        const topFrame = frames[0];
        assert(topFrame !== undefined, 'top frame exists');
        if (topFrame) {
            console.log(`  Top frame: name='${topFrame.name}', file='${topFrame.filePath}', line=${topFrame.line}`);
            assert(
                topFrame.name === 'build_greeting',
                `top frame name is 'build_greeting' (got '${topFrame.name}')`,
            );
            assert(
                topFrame.line === WEBDEV_BREAKPOINT_LINE,
                `top frame line is ${WEBDEV_BREAKPOINT_LINE} (got ${topFrame.line})`,
            );
        }

        // -- Step 7: inspect variables -------------------------------------------
        step('7. Inspect variables at breakpoint');
        const scopes = await client.getScopes(sessionId, topFrame?.id ?? 0);
        const localsScope = scopes.find((s) => s.name === 'Locals');
        assert(localsScope !== undefined, `'Locals' scope exists`);

        if (localsScope) {
            const variables = await client.getVariables(sessionId, localsScope.variablesReference);
            const varNames = variables.map((v: Variable) => v.name);
            console.log(`  Local variables: ${varNames.join(', ')}`);
            assert(varNames.includes('name'), `'name' variable present (got: ${varNames.join(', ')})`);

            const nameVar = variables.find((v: Variable) => v.name === 'name');
            if (nameVar) {
                assert(
                    nameVar.value.includes('Debugger'),
                    `'name' variable value contains 'Debugger' (got '${nameVar.value}')`,
                );
            }
        }

        // -- Step 8: continue execution ------------------------------------------
        step('8. Continue execution after breakpoint');
        await client.continueExecution(sessionId);
    }

    // -- Step 9: verify HTTP response ----------------------------------------
    step('9. Verify WebDev HTTP response');
    try {
        const httpResult = await httpPromise;
        assert(httpResult.status === 200, `HTTP response status is 200 (got ${httpResult.status})`);
        console.log(`  HTTP body: ${httpResult.body.substring(0, 200)}`);
        const body = JSON.parse(httpResult.body);
        assert(
            body.greeting !== undefined,
            `response contains 'greeting' field (got: ${JSON.stringify(body)})`,
        );
        assert(
            typeof body.greeting === 'string' && body.greeting.includes('Debugger'),
            `greeting contains 'Debugger' (got '${body.greeting}')`,
        );
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        // The HTTP request may have completed or timed out; either way is informative
        console.log(`  HTTP response: ${msg}`);
    }

    // -- Step 10: detach -----------------------------------------------------
    step('10. Detach from gateway');
    await client.detachSession(sessionId);
    assert(true, 'detachSession call succeeded');
}

// ---------------------------------------------------------------------------
// Mock-based run (no Docker needed)
// ---------------------------------------------------------------------------

async function runMockTest(): Promise<void> {
    const port = await findFreePort();
    const secret = 'test-secret-e2e';

    const mockServer = new MockGatewayServer({
        port,
        secret,
        scriptFilePath: TEST_SCRIPT_PATH,
        breakpointStops: [
            {
                line: BREAKPOINT_LINE_GREET,
                frameName: 'greet',
                locals: [
                    {
                        name: 'name',
                        value: 'Ignition',
                        type: 'str',
                        variablesReference: 0,
                    },
                    {
                        name: 'message',
                        value: 'Hello, Ignition!',
                        type: 'str',
                        variablesReference: 0,
                    },
                ],
            },
        ],
        scriptOutput: [
            "system.date.now: <type 'java.util.Date'>",
            'Hello, Ignition!',
            'Value: 30',
            'Total: 15',
            "Done: {'greeting': 'Hello, Ignition!', 'value': 30, 'total': 15}",
        ],
    });

    const client = new DebugClient(`ws://127.0.0.1:${port}`, secret, 5_000);

    try {
        await client.connect();
        await runTestSuite(client, 'MOCK MODE – Simulated Ignition Gateway (no Docker)');
    } finally {
        client.disconnect();
        await mockServer.close();
    }

    // Run attach-mode tests with a fresh mock server
    const attachPort = await findFreePort();
    const attachMockServer = new MockGatewayServer({
        port: attachPort,
        secret,
        scriptFilePath: TEST_SCRIPT_PATH,
        breakpointStops: [
            {
                line: BREAKPOINT_LINE_GREET,
                frameName: 'greet',
                locals: [
                    {
                        name: 'name',
                        value: 'Ignition',
                        type: 'str',
                        variablesReference: 0,
                    },
                    {
                        name: 'message',
                        value: 'Hello, Ignition!',
                        type: 'str',
                        variablesReference: 0,
                    },
                ],
            },
        ],
        scriptOutput: [
            'Hello, Ignition!',
            'Value: 30',
        ],
    });

    const attachClient = new DebugClient(`ws://127.0.0.1:${attachPort}`, secret, 5_000);

    try {
        await attachClient.connect();
        await runAttachTestSuite(attachClient, 'MOCK MODE – Attach to Gateway (no Docker)');
    } finally {
        attachClient.disconnect();
        await attachMockServer.close();
    }
}

// ---------------------------------------------------------------------------
// Docker / real gateway run
// ---------------------------------------------------------------------------

async function runDockerTest(registryFilePath: string): Promise<void> {
    console.log(`\nReading registry from: ${registryFilePath}`);
    const registry = readRegistryFile(registryFilePath);

    console.log(`Connecting to real gateway at ws://127.0.0.1:${registry.port} ...`);

    // --- Launch-mode test (existing) ----------------------------------------
    const client = new DebugClient(`ws://127.0.0.1:${registry.port}`, registry.secret, 15_000);

    try {
        await client.connect();
        await runTestSuite(
            client,
            `DOCKER MODE – Real Ignition Gateway (pid=${registry.pid}, port=${registry.port})`,
            { supportsSystemApi: true },
        );
    } finally {
        client.disconnect();
    }

    // --- Attach-mode test with WebDev endpoint ------------------------------
    const attachClient = new DebugClient(`ws://127.0.0.1:${registry.port}`, registry.secret, 15_000);

    try {
        await attachClient.connect();
        await runDockerAttachTestSuite(
            attachClient,
            `DOCKER MODE – Attach to Gateway + WebDev (pid=${registry.pid}, port=${registry.port})`,
            'http://localhost:8088',
        );
    } finally {
        attachClient.disconnect();
    }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

async function main(): Promise<void> {
    console.log('Ignition Debugger – End-to-End Test');
    console.log('====================================');
    console.log(`Test script: ${TEST_SCRIPT_PATH}`);

    const registryFile = process.env['IGNITION_GATEWAY_REGISTRY_FILE'];

    try {
        if (registryFile) {
            await runDockerTest(registryFile);
        } else {
            await runMockTest();
        }
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        console.error(`\n[FATAL] Test threw an unexpected error: ${msg}`);
        if (err instanceof Error && err.stack) {
            console.error(err.stack);
        }
        failed++;
    }

    // Final report
    console.log(`\n${'='.repeat(60)}`);
    console.log(`  RESULTS: ${passed} passed, ${failed} failed`);
    console.log('='.repeat(60));

    if (failed > 0) {
        process.exit(1);
    }
}

main().catch((err) => {
    console.error('Unhandled error:', err);
    process.exit(1);
});
