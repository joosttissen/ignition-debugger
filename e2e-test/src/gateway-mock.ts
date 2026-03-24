/**
 * gateway-mock.ts
 *
 * A Node.js mock of the Ignition Gateway WebSocket debug server.
 *
 * Simulates the Java DebugWebSocketServer behaviour so that the E2E test can
 * run without a real Ignition gateway.  The mock implements the full
 * JSON-RPC 2.0 debug protocol:
 *
 *   authenticate       → verifies the shared secret
 *   ping               → connectivity check
 *   debug.startSession → registers a script session, returns sessionId
 *   debug.setBreakpoints → stores breakpoints, returns verified list
 *   debug.run          → starts mock execution; emits stopped at each bp, then terminated
 *   debug.stopSession  → aborts a session
 *   debug.getStackTrace → returns fake stack frames
 *   debug.getScopes    → returns Locals / Globals scopes
 *   debug.getVariables → returns variables for a scope
 *   debug.continue     → resumes to next breakpoint or terminates
 *   debug.stepOver     → same as continue (simplified mock)
 *   debug.stepInto     → same as continue (simplified mock)
 *   debug.stepOut      → same as continue (simplified mock)
 *   debug.evaluate     → evaluates a simple expression
 *
 * Events pushed to the client:
 *   debug.event.stopped      → script paused at a breakpoint / step
 *   debug.event.output       → print() output
 *   debug.event.terminated   → script finished
 */

import * as net from 'net';
import WebSocket, { WebSocketServer } from 'ws';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface JsonRpcRequest {
    jsonrpc: '2.0';
    id: number | string;
    method: string;
    params?: unknown;
}

interface JsonRpcResponse {
    jsonrpc: '2.0';
    id: number | string | null;
    result?: unknown;
    error?: { code: number; message: string };
}

interface JsonRpcNotification {
    jsonrpc: '2.0';
    method: string;
    params?: unknown;
}

export interface Variable {
    name: string;
    value: string;
    type: string;
    variablesReference: number;
}

export interface BreakpointStop {
    /** 1-based line number where the script pauses */
    line: number;
    /** Stack frame name shown in VS Code */
    frameName: string;
    /** Local variables visible at this pause point */
    locals: Variable[];
}

export interface MockGatewayOptions {
    /** TCP port to listen on */
    port: number;
    /** Shared secret for authentication */
    secret: string;
    /** Path reported back in stack frames (should match what the test uses) */
    scriptFilePath: string;
    /** Ordered list of breakpoint stops that the mock will simulate */
    breakpointStops: BreakpointStop[];
    /** Lines of print() output emitted before termination */
    scriptOutput?: string[];
}

// ---------------------------------------------------------------------------
// Helper – find a free TCP port
// ---------------------------------------------------------------------------

export function findFreePort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.listen(0, '127.0.0.1', () => {
            const addr = server.address();
            if (!addr || typeof addr === 'string') {
                reject(new Error('Could not determine port'));
                return;
            }
            const port = addr.port;
            server.close(() => resolve(port));
        });
        server.on('error', reject);
    });
}

// ---------------------------------------------------------------------------
// Mock session
// ---------------------------------------------------------------------------

class MockSession {
    private breakpointLines = new Set<number>();
    private pendingStops: BreakpointStop[];
    private resumeCallback: (() => void) | null = null;
    private aborted = false;
    /** The stop we are currently paused at (null when executing). */
    private currentStop: BreakpointStop | null = null;

    constructor(
        public readonly sessionId: string,
        public readonly filePath: string,
        private readonly stops: BreakpointStop[],
        private readonly output: string[],
        private readonly emitEvent: (method: string, params: unknown) => void,
    ) {
        this.pendingStops = [...stops];
    }

    setBreakpoints(lines: number[]): void {
        this.breakpointLines = new Set(lines);
    }

    /** Kick off mock script execution in the background. */
    execute(): void {
        void this.run();
    }

    private async run(): Promise<void> {
        for (const stop of this.pendingStops) {
            if (this.aborted) return;

            // Only pause if a breakpoint is set on this line
            if (this.breakpointLines.has(stop.line)) {
                // Small delay to let the client receive the debug.run response first
                await delay(20);
                if (this.aborted) return;

                this.currentStop = stop;
                this.emitEvent('debug.event.stopped', {
                    reason: 'breakpoint',
                    description: `Breakpoint at line ${stop.line}`,
                    threadId: 1,
                    allThreadsStopped: true,
                    line: stop.line,
                });

                // Wait until the client sends continue/step
                await this.waitForResume();
                this.currentStop = null;
                if (this.aborted) return;
            }
        }

        await delay(10);
        if (this.aborted) return;

        // Emit any print() output
        for (const line of this.output) {
            this.emitEvent('debug.event.output', {
                category: 'stdout',
                output: line + '\n',
            });
        }

        this.emitEvent('debug.event.terminated', {});
    }

    private waitForResume(): Promise<void> {
        return new Promise<void>((resolve) => {
            this.resumeCallback = resolve;
        });
    }

    resume(): void {
        const cb = this.resumeCallback;
        this.resumeCallback = null;
        cb?.();
    }

    abort(): void {
        this.aborted = true;
        this.resume();
    }

    /** Return the stop the session is currently paused at, or undefined if executing. */
    getCurrentStop(): BreakpointStop | undefined {
        return this.currentStop ?? undefined;
    }
}

function delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Mock gateway server
// ---------------------------------------------------------------------------

export class MockGatewayServer {
    private readonly wss: WebSocketServer;
    private client: WebSocket | null = null;
    private sessions = new Map<string, MockSession>();
    private sessionCounter = 0;
    private pausedStop: BreakpointStop | null = null;

    constructor(private readonly options: MockGatewayOptions) {
        this.wss = new WebSocketServer({ host: '127.0.0.1', port: options.port });
        this.wss.on('connection', (ws) => this.handleConnection(ws));
    }

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    close(): Promise<void> {
        return new Promise((resolve, reject) => {
            // Abort all sessions
            for (const session of this.sessions.values()) {
                session.abort();
            }
            this.sessions.clear();
            this.wss.close((err) => (err ? reject(err) : resolve()));
        });
    }

    // ------------------------------------------------------------------------
    // Connection handling
    // ------------------------------------------------------------------------

    private handleConnection(ws: WebSocket): void {
        this.client = ws;

        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data.toString()) as JsonRpcRequest;
                this.handleMessage(ws, msg);
            } catch {
                // ignore parse errors in mock
            }
        });

        ws.on('close', () => {
            if (this.client === ws) {
                this.client = null;
            }
        });
    }

    private handleMessage(ws: WebSocket, msg: JsonRpcRequest): void {
        const { id, method, params } = msg;
        const p = params as Record<string, unknown> | undefined;

        switch (method) {
            case 'authenticate':
                this.handleAuthenticate(ws, id, p);
                break;
            case 'ping':
                this.sendResult(ws, id, { status: 'ok', timestamp: Date.now() });
                break;
            case 'debug.startSession':
                this.handleStartSession(ws, id, p);
                break;
            case 'debug.setBreakpoints':
                this.handleSetBreakpoints(ws, id, p);
                break;
            case 'debug.run':
                this.handleRun(ws, id, p);
                break;
            case 'debug.stopSession':
                this.handleStopSession(ws, id, p);
                break;
            case 'debug.getStackTrace':
                this.handleGetStackTrace(ws, id, p);
                break;
            case 'debug.getScopes':
                this.handleGetScopes(ws, id, p);
                break;
            case 'debug.getVariables':
                this.handleGetVariables(ws, id, p);
                break;
            case 'debug.continue':
            case 'debug.stepOver':
            case 'debug.stepInto':
            case 'debug.stepOut': {
                const sessionId = p?.['sessionId'] as string | undefined ?? '';
                const session = this.sessions.get(sessionId);
                this.pausedStop = null;
                session?.resume();
                this.sendResult(ws, id, { success: true });
                break;
            }
            case 'debug.evaluate':
                this.handleEvaluate(ws, id, p);
                break;
            default:
                this.sendError(ws, id, -32601, `Method not found: ${method}`);
        }
    }

    // ------------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------------

    private handleAuthenticate(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const secret = (params?.['secret'] as string | undefined) ?? '';
        if (secret === this.options.secret) {
            this.sendResult(ws, id, { success: true, moduleVersion: '0.1.0' });
        } else {
            this.sendResult(ws, id, { success: false, error: 'Invalid secret' });
        }
    }

    private handleStartSession(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const filePath = (params?.['filePath'] as string | undefined) ?? this.options.scriptFilePath;
        const sessionId = `session-${++this.sessionCounter}`;

        const session = new MockSession(
            sessionId,
            filePath,
            this.options.breakpointStops,
            this.options.scriptOutput ?? [],
            (method, p) => this.sendNotification(method, p),
        );

        // Register the session so subsequent requests can look it up by sessionId
        this.sessions.set(sessionId, session);

        this.sendResult(ws, id, { success: true, sessionId });
    }

    private handleSetBreakpoints(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        const bpArray = (params?.['breakpoints'] as Array<{ line: number }> | undefined) ?? [];
        const lines = bpArray.map((b) => b.line);

        const session = this.sessions.get(sessionId);
        if (!session) {
            this.sendError(ws, id, -32002, `Session not found: ${sessionId}`);
            return;
        }

        session.setBreakpoints(lines);

        const result = bpArray.map((bp, i) => ({
            id: i + 1,
            verified: true,
            line: bp.line,
        }));
        this.sendResult(ws, id, { breakpoints: result });
    }

    private handleRun(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        const session = this.sessions.get(sessionId);
        if (!session) {
            this.sendError(ws, id, -32002, `Session not found: ${sessionId}`);
            return;
        }
        this.sendResult(ws, id, { success: true });
        session.execute();
    }

    private handleStopSession(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        const session = this.sessions.get(sessionId);
        if (session) {
            session.abort();
            this.sessions.delete(sessionId);
        }
        this.sendResult(ws, id, { success: true });
    }

    private handleGetStackTrace(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        const session = this.sessions.get(sessionId);
        if (!session) {
            this.sendResult(ws, id, { stackFrames: [], totalFrames: 0 });
            return;
        }

        const stop = session.getCurrentStop() ?? this.options.breakpointStops[0];
        const frames = stop
            ? [
                  {
                      id: 0,
                      name: stop.frameName,
                      filePath: session.filePath,
                      line: stop.line,
                      column: 0,
                  },
              ]
            : [];

        this.sendResult(ws, id, { stackFrames: frames, totalFrames: frames.length });
    }

    private handleGetScopes(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        if (!this.sessions.has(sessionId)) {
            this.sendResult(ws, id, { scopes: [] });
            return;
        }

        this.sendResult(ws, id, {
            scopes: [
                { name: 'Locals', variablesReference: 1000, expensive: false },
                { name: 'Globals', variablesReference: 1001, expensive: true },
            ],
        });
    }

    private handleGetVariables(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const sessionId = (params?.['sessionId'] as string | undefined) ?? '';
        const varRef = (params?.['variablesReference'] as number | undefined) ?? 0;
        const session = this.sessions.get(sessionId);
        if (!session) {
            this.sendResult(ws, id, { variables: [] });
            return;
        }

        const stop = session.getCurrentStop() ?? this.options.breakpointStops[0];

        if (varRef === 1000) {
            // Locals
            this.sendResult(ws, id, { variables: stop?.locals ?? [] });
        } else {
            // Globals – return empty for simplicity
            this.sendResult(ws, id, { variables: [] });
        }
    }

    private handleEvaluate(
        ws: WebSocket,
        id: number | string,
        params: Record<string, unknown> | undefined,
    ): void {
        const expression = (params?.['expression'] as string | undefined) ?? '';
        // Simple mock evaluation: just echo the expression back
        this.sendResult(ws, id, {
            result: `<mock: ${expression}>`,
            type: 'str',
            variablesReference: 0,
        });
    }

    // ------------------------------------------------------------------------
    // Messaging helpers
    // ------------------------------------------------------------------------

    private sendResult(ws: WebSocket, id: number | string, result: unknown): void {
        const response: JsonRpcResponse = { jsonrpc: '2.0', id, result };
        ws.send(JSON.stringify(response));
    }

    private sendError(ws: WebSocket, id: number | string, code: number, message: string): void {
        const response: JsonRpcResponse = { jsonrpc: '2.0', id, error: { code, message } };
        ws.send(JSON.stringify(response));
    }

    private sendNotification(method: string, params: unknown): void {
        const ws = this.client;
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        const notification: JsonRpcNotification = { jsonrpc: '2.0', method, params };
        ws.send(JSON.stringify(notification));
    }
}
