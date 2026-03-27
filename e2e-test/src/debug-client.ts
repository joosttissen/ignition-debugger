/**
 * debug-client.ts
 *
 * A lightweight, standalone debug protocol client that mirrors what the VS Code
 * extension does (ConnectionManager + IgnitionDebugAdapter), but runs as plain
 * Node.js so the E2E test can execute without VS Code.
 *
 * Usage:
 *   const client = new DebugClient('ws://127.0.0.1:<port>', '<secret>');
 *   await client.connect();
 *   const { sessionId } = await client.startSession(code, filePath);
 *   await client.setBreakpoints(sessionId, filePath, [{ line: 2 }]);
 *   client.once('stopped', (body) => { ... });
 *   await client.run(sessionId);
 */

import WebSocket from 'ws';
import { EventEmitter } from 'events';

// ---------------------------------------------------------------------------
// JSON-RPC types
// ---------------------------------------------------------------------------

interface JsonRpcRequest {
    jsonrpc: '2.0';
    id: number;
    method: string;
    params?: unknown;
}

interface JsonRpcResponse {
    jsonrpc: '2.0';
    id: number | null;
    result?: unknown;
    error?: { code: number; message: string; data?: unknown };
}

interface JsonRpcNotification {
    jsonrpc: '2.0';
    method: string;
    params?: unknown;
}

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export interface BreakpointRequest {
    line: number;
    condition?: string;
}

export interface Breakpoint {
    id: number;
    verified: boolean;
    line: number;
    message?: string;
}

export interface StackFrame {
    id: number;
    name: string;
    filePath: string;
    line: number;
    column: number;
}

export interface Scope {
    name: string;
    variablesReference: number;
    expensive: boolean;
}

export interface Variable {
    name: string;
    value: string;
    type: string;
    variablesReference: number;
}

export interface StoppedEventBody {
    reason: string;
    description?: string;
    threadId: number;
    line?: number;
}

export interface OutputEventBody {
    category: 'stdout' | 'stderr' | string;
    output: string;
}

// ---------------------------------------------------------------------------
// DebugClient
// ---------------------------------------------------------------------------

interface PendingRequest {
    resolve: (result: unknown) => void;
    reject: (err: Error) => void;
    timeout: ReturnType<typeof setTimeout>;
}

export class DebugClient extends EventEmitter {
    private ws: WebSocket | null = null;
    private idCounter = 0;
    private pending = new Map<number, PendingRequest>();
    private readonly timeoutMs: number;
    private collectedOutput: OutputEventBody[] = [];

    constructor(
        private readonly url: string,
        private readonly secret: string,
        timeoutMs = 30_000,
    ) {
        super();
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------------

    connect(): Promise<void> {
        return new Promise((resolve, reject) => {
            const ws = new WebSocket(this.url);
            this.ws = ws;

            ws.on('open', () => {
                this.authenticate()
                    .then(() => resolve())
                    .catch(reject);
            });

            ws.on('message', (data) => this.handleMessage(data.toString()));
            ws.on('error', reject);
            ws.on('close', () => this.emit('close'));
        });
    }

    disconnect(): void {
        for (const [, req] of this.pending) {
            clearTimeout(req.timeout);
            req.reject(new Error('Connection closed'));
        }
        this.pending.clear();
        this.ws?.close();
        this.ws = null;
    }

    // ------------------------------------------------------------------------
    // Debug protocol methods
    // ------------------------------------------------------------------------

    async startSession(
        code: string,
        filePath: string,
    ): Promise<{ sessionId: string }> {
        return this.request<{ success: boolean; sessionId: string; error?: string }>(
            'debug.startSession',
            { code, filePath, modulePath: filePath },
        ).then((r) => {
            if (!r.success || !r.sessionId) {
                throw new Error(r.error ?? 'Failed to start session');
            }
            return { sessionId: r.sessionId };
        });
    }

    async attachSession(projectName?: string): Promise<{ sessionId: string }> {
        return this.request<{ success: boolean; sessionId: string; error?: string }>(
            'debug.attach',
            { projectName: projectName ?? '' },
        ).then((r) => {
            if (!r.success || !r.sessionId) {
                throw new Error(r.error ?? 'Failed to attach');
            }
            return { sessionId: r.sessionId };
        });
    }

    async detachSession(sessionId: string): Promise<void> {
        await this.request('debug.detach', { sessionId }).catch(() => undefined);
    }

    async setBreakpoints(
        sessionId: string,
        filePath: string,
        breakpoints: BreakpointRequest[],
    ): Promise<Breakpoint[]> {
        const result = await this.request<{ breakpoints: Breakpoint[] }>(
            'debug.setBreakpoints',
            { sessionId, filePath, breakpoints },
        );
        return result.breakpoints;
    }

    async run(sessionId: string): Promise<void> {
        await this.request('debug.run', { sessionId });
    }

    async getStackTrace(sessionId: string): Promise<StackFrame[]> {
        const result = await this.request<{ stackFrames: StackFrame[]; totalFrames: number }>(
            'debug.getStackTrace',
            { sessionId, threadId: 1 },
        );
        return result.stackFrames;
    }

    async getScopes(sessionId: string, frameId: number): Promise<Scope[]> {
        const result = await this.request<{ scopes: Scope[] }>(
            'debug.getScopes',
            { sessionId, frameId },
        );
        return result.scopes;
    }

    async getVariables(sessionId: string, variablesReference: number): Promise<Variable[]> {
        const result = await this.request<{ variables: Variable[] }>(
            'debug.getVariables',
            { sessionId, variablesReference },
        );
        return result.variables;
    }

    async continueExecution(sessionId: string): Promise<void> {
        await this.request('debug.continue', { sessionId, threadId: 1 });
    }

    async stepOver(sessionId: string): Promise<void> {
        await this.request('debug.stepOver', { sessionId, threadId: 1 });
    }

    async evaluate(sessionId: string, expression: string, frameId?: number): Promise<string> {
        const result = await this.request<{ result: string; type: string }>(
            'debug.evaluate',
            { sessionId, expression, frameId, context: 'repl' },
        );
        return result.result;
    }

    async stopSession(sessionId: string): Promise<void> {
        await this.request('debug.stopSession', { sessionId }).catch(() => undefined);
    }

    ping(): Promise<{ status: string }> {
        return this.request<{ status: string }>('ping');
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private authenticate(): Promise<void> {
        return this.request<{ success: boolean; error?: string }>(
            'authenticate',
            {
                secret: this.secret,
                clientName: 'Ignition Debugger E2E Test',
                clientVersion: '1.0.0',
            },
        ).then((r) => {
            if (!r.success) {
                throw new Error(r.error ?? 'Authentication failed');
            }
        });
    }

    private request<T>(method: string, params?: unknown): Promise<T> {
        if (!this.ws) {
            return Promise.reject(new Error('Not connected'));
        }
        const id = ++this.idCounter;
        const msg: JsonRpcRequest = { jsonrpc: '2.0', id, method, params };

        return new Promise<T>((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`Request timed out: ${method}`));
            }, this.timeoutMs);

            this.pending.set(id, {
                resolve: resolve as (r: unknown) => void,
                reject,
                timeout,
            });

            this.ws!.send(JSON.stringify(msg));
        });
    }

    private handleMessage(raw: string): void {
        let msg: unknown;
        try {
            msg = JSON.parse(raw);
        } catch {
            return;
        }

        const m = msg as Record<string, unknown>;

        // Notification (no id)
        if (typeof m['method'] === 'string' && m['id'] === undefined) {
            this.handleNotification(m as unknown as JsonRpcNotification);
            return;
        }

        // Response (has id)
        if (m['id'] !== undefined && m['id'] !== null) {
            this.handleResponse(m as unknown as JsonRpcResponse);
        }
    }

    private handleResponse(response: JsonRpcResponse): void {
        const id = response.id;
        if (id === null || id === undefined) return;
        const req = this.pending.get(id as number);
        if (!req) return;

        clearTimeout(req.timeout);
        this.pending.delete(id as number);

        if (response.error) {
            req.reject(new Error(response.error.message));
        } else {
            req.resolve(response.result);
        }
    }

    private handleNotification(notification: JsonRpcNotification): void {
        const method = notification.method;

        if (method.startsWith('debug.event.')) {
            const eventType = method.substring('debug.event.'.length);
            // Emit both the specific event type and a generic 'debugEvent' event
            this.emit(eventType, notification.params);
            this.emit('debugEvent', { event: eventType, body: notification.params });
        }
    }

    // ------------------------------------------------------------------------
    // Typed event helpers
    // ------------------------------------------------------------------------

    /** Returns a Promise that resolves when the next 'stopped' event arrives. */
    waitForStopped(timeoutMs = 10_000): Promise<StoppedEventBody> {
        return this.waitForEvent<StoppedEventBody>('stopped', timeoutMs);
    }

    /** Returns a Promise that resolves when the 'terminated' event arrives. */
    waitForTerminated(timeoutMs = 15_000): Promise<void> {
        return this.waitForEvent<void>('terminated', timeoutMs);
    }

    /**
     * Start collecting all `output` events into an internal buffer.
     * Call {@link getCollectedOutput} after termination to inspect them.
     */
    startCollectingOutput(): void {
        this.collectedOutput = [];
        this.on('output', (body: OutputEventBody) => {
            this.collectedOutput.push(body);
        });
    }

    /** Return all `output` events collected since {@link startCollectingOutput}. */
    getCollectedOutput(): OutputEventBody[] {
        return [...this.collectedOutput];
    }

    private waitForEvent<T>(event: string, timeoutMs: number): Promise<T> {
        return new Promise<T>((resolve, reject) => {
            const timer = setTimeout(() => {
                this.off(event, handler);
                reject(new Error(`Timed out waiting for '${event}' event`));
            }, timeoutMs);

            const handler = (body: T) => {
                clearTimeout(timer);
                resolve(body);
            };

            this.once(event, handler);
        });
    }
}
