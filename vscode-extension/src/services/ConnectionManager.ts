/**
 * ConnectionManager handles WebSocket connections to Ignition Designer instances.
 *
 * Communication uses JSON-RPC 2.0 over WebSocket.  Debug events are pushed from
 * the Designer module as JSON-RPC notifications (no id field).
 */

import WebSocket from 'ws';
import { DesignerInstance } from './DiscoveryService';

// ---- JSON-RPC types --------------------------------------------------------

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

// ---- Public types ----------------------------------------------------------

export interface DebugEventData {
    event: string;
    body?: unknown;
}

export enum ConnectionState {
    DISCONNECTED = 'disconnected',
    CONNECTING = 'connecting',
    CONNECTED = 'connected',
    ERROR = 'error',
}

// ---- Implementation --------------------------------------------------------

interface PendingRequest {
    resolve: (result: unknown) => void;
    reject: (err: Error) => void;
    timeout: ReturnType<typeof setTimeout>;
}

export class ConnectionManager {
    private static readonly REQUEST_TIMEOUT_MS = 30_000;

    private ws: WebSocket | null = null;
    private state: ConnectionState = ConnectionState.DISCONNECTED;
    private connectedDesigner: DesignerInstance | null = null;
    private idCounter = 0;
    private pending = new Map<number, PendingRequest>();

    private stateListeners: Array<(s: ConnectionState, d: DesignerInstance | null) => void> = [];
    private debugEventListeners: Array<(e: DebugEventData) => void> = [];

    // ---------- Public API --------------------------------------------------

    getConnectionState(): ConnectionState {
        return this.state;
    }

    getConnectedDesigner(): DesignerInstance | null {
        return this.connectedDesigner;
    }

    onConnectionStateChanged(cb: (s: ConnectionState, d: DesignerInstance | null) => void): void {
        this.stateListeners.push(cb);
    }

    onDebugEvent(cb: (e: DebugEventData) => void): void {
        this.debugEventListeners.push(cb);
    }

    offDebugEvent(cb: (e: DebugEventData) => void): void {
        const idx = this.debugEventListeners.indexOf(cb);
        if (idx >= 0) {
            this.debugEventListeners.splice(idx, 1);
        }
    }

    async connect(designer: DesignerInstance, requestTimeoutMs?: number): Promise<void> {
        if (this.ws) {
            await this.disconnect();
        }

        this.setState(ConnectionState.CONNECTING, designer);
        const timeout = requestTimeoutMs ?? ConnectionManager.REQUEST_TIMEOUT_MS;

        return new Promise((resolve, reject) => {
            const url = `ws://127.0.0.1:${designer.port}`;
            const ws = new WebSocket(url);
            this.ws = ws;

            ws.on('open', () => {
                this.connectedDesigner = designer;
                this.authenticate(designer.secret, timeout)
                    .then(() => {
                        this.setState(ConnectionState.CONNECTED, designer);
                        resolve();
                    })
                    .catch((err: unknown) => {
                        this.setState(ConnectionState.ERROR, designer);
                        reject(err instanceof Error ? err : new Error(String(err)));
                    });
            });

            ws.on('message', (data) => {
                this.handleMessage(data.toString());
            });

            ws.on('close', () => {
                this.handleClose();
            });

            ws.on('error', (err) => {
                this.setState(ConnectionState.ERROR, designer);
                reject(err);
            });
        });
    }

    async disconnect(): Promise<void> {
        for (const [id, req] of this.pending) {
            clearTimeout(req.timeout);
            req.reject(new Error('Connection closed'));
            this.pending.delete(id);
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        this.connectedDesigner = null;
        this.setState(ConnectionState.DISCONNECTED, null);
    }

    dispose(): void {
        void this.disconnect();
        this.stateListeners = [];
        this.debugEventListeners = [];
    }

    async sendRequest<T>(method: string, params?: unknown, timeoutMs?: number): Promise<T> {
        if (this.state !== ConnectionState.CONNECTED && method !== 'authenticate') {
            throw new Error('Not connected to Designer');
        }
        if (!this.ws) {
            throw new Error('WebSocket not available');
        }

        const id = ++this.idCounter;
        const request: JsonRpcRequest = { jsonrpc: '2.0', id, method, params };
        const resolvedTimeout = timeoutMs ?? ConnectionManager.REQUEST_TIMEOUT_MS;

        return new Promise<T>((resolve, reject) => {
            const timeout = setTimeout(() => {
                this.pending.delete(id);
                reject(new Error(`Request timed out: ${method}`));
            }, resolvedTimeout);

            this.pending.set(id, {
                resolve: resolve as (r: unknown) => void,
                reject,
                timeout,
            });

            this.ws!.send(JSON.stringify(request));
        });
    }

    // ---------- Private helpers ---------------------------------------------

    private async authenticate(secret: string, timeoutMs: number): Promise<void> {
        const result = await this.sendRequest<{ success: boolean; error?: string }>(
            'authenticate',
            { secret, clientName: 'Ignition Debugger VS Code Extension', clientVersion: '0.1.0' },
            timeoutMs
        );
        if (!result.success) {
            throw new Error(result.error ?? 'Authentication failed');
        }
    }

    private handleMessage(raw: string): void {
        let msg: unknown;
        try {
            msg = JSON.parse(raw);
        } catch {
            console.error('[IgnitionDebugger] Failed to parse message:', raw);
            return;
        }

        const m = msg as Record<string, unknown>;

        // Notification: has "method" but no "id"
        if (typeof m['method'] === 'string' && m['id'] === undefined) {
            this.handleNotification(m as unknown as JsonRpcNotification);
            return;
        }

        // Response: has "id"
        if (m['id'] !== undefined && m['id'] !== null) {
            this.handleResponse(m as unknown as JsonRpcResponse);
        }
    }

    private handleResponse(response: JsonRpcResponse): void {
        const id = response.id;
        if (id === null || id === undefined) {
            return;
        }
        const req = this.pending.get(id as number);
        if (!req) {
            return;
        }
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

        // Debug events are prefixed with "debug.event."
        if (method.startsWith('debug.event.')) {
            const eventType = method.substring('debug.event.'.length);
            const event: DebugEventData = { event: eventType, body: notification.params };
            for (const cb of this.debugEventListeners) {
                try {
                    cb(event);
                } catch (err) {
                    console.error('[IgnitionDebugger] Error in debug event callback:', err);
                }
            }
        }
    }

    private handleClose(): void {
        // Reject all pending
        for (const [id, req] of this.pending) {
            clearTimeout(req.timeout);
            req.reject(new Error('Connection closed'));
            this.pending.delete(id);
        }
        this.connectedDesigner = null;
        this.setState(ConnectionState.DISCONNECTED, null);
    }

    private setState(state: ConnectionState, designer: DesignerInstance | null): void {
        this.state = state;
        for (const cb of this.stateListeners) {
            try {
                cb(state, designer);
            } catch (err) {
                console.error('[IgnitionDebugger] Error in state listener:', err);
            }
        }
    }
}
