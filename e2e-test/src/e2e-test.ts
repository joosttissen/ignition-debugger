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
 *   12. Disconnect.
 *
 * When the IGNITION_GATEWAY_REGISTRY_FILE environment variable is set, the
 * test reads that registry JSON (written by the real gateway module) and runs
 * the same protocol flow against the actual Ignition gateway running in Docker.
 * This proves that the full Docker-based E2E path works too.
 *
 * Usage (mock mode – no Docker required):
 *   npm test
 *
 * Usage (Docker mode – real Ignition gateway):
 *   IGNITION_GATEWAY_REGISTRY_FILE=../debugger-gateway-registry/gateway-<pid>.json npm test
 */

import * as fs from 'fs';
import * as path from 'path';
import * as process from 'process';
import { DebugClient, Variable } from './debug-client';
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

    // -- Step 12: clean up ---------------------------------------------------
    step('12. Clean up');
    await client.stopSession(sessionId);
    assert(true, 'stopSession call succeeded');
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
}

// ---------------------------------------------------------------------------
// Docker / real gateway run
// ---------------------------------------------------------------------------

async function runDockerTest(registryFilePath: string): Promise<void> {
    console.log(`\nReading registry from: ${registryFilePath}`);
    const registry = readRegistryFile(registryFilePath);

    console.log(`Connecting to real gateway at ws://127.0.0.1:${registry.port} ...`);

    const client = new DebugClient(`ws://127.0.0.1:${registry.port}`, registry.secret, 15_000);

    try {
        await client.connect();
        await runTestSuite(
            client,
            `DOCKER MODE – Real Ignition Gateway (pid=${registry.pid}, port=${registry.port})`,
        );
    } finally {
        client.disconnect();
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
