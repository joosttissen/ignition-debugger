/**
 * IgnitionDebugAdapter – VS Code Debug Adapter Protocol implementation.
 *
 * Translates DAP messages from VS Code into JSON-RPC calls to the Ignition
 * Designer's debug WebSocket server, and forwards Designer notifications back
 * to VS Code as DAP events.
 *
 * Protocol reference: https://microsoft.github.io/debug-adapter-protocol/
 * Inspired by FlintDebugAdapter from the ignition-flint project.
 */

import * as vscode from 'vscode';
import { ConnectionManager, ConnectionState, DebugEventData } from '../services/ConnectionManager';
import { DiscoveryService } from '../services/DiscoveryService';

// ---- Minimal DAP type definitions -----------------------------------------

interface DapRequest {
    type: 'request';
    seq: number;
    command: string;
    arguments?: unknown;
}

interface DapResponse {
    type: 'response';
    seq: number;
    request_seq: number;
    success: boolean;
    command: string;
    message?: string;
    body?: unknown;
}

interface DapEvent {
    type: 'event';
    seq: number;
    event: string;
    body?: unknown;
}

// ---- DAP argument shapes ---------------------------------------------------

interface InitializeArgs {
    adapterID: string;
    linesStartAt1?: boolean;
    columnsStartAt1?: boolean;
}

interface LaunchArgs extends vscode.DebugConfiguration {
    program: string;
    stopOnEntry?: boolean;
    gatewayUrl?: string;
}

interface SetBreakpointsArgs {
    source: { path?: string; name?: string };
    breakpoints?: Array<{ line: number; condition?: string; hitCondition?: string }>;
}

interface StackTraceArgs {
    threadId: number;
    startFrame?: number;
    levels?: number;
}

interface ScopesArgs {
    frameId: number;
}

interface VariablesArgs {
    variablesReference: number;
    start?: number;
    count?: number;
}

interface ContinueArgs {
    threadId: number;
}

interface StepArgs {
    threadId: number;
}

interface EvaluateArgs {
    expression: string;
    frameId?: number;
    context?: string;
}

// ---- Helpers ---------------------------------------------------------------

/**
 * Extract the Ignition project name from a file path.
 * Ignition stores project files at: .../projects/<projectName>/...
 */
function extractProjectName(filePath: string): string {
    const normalized = filePath.replace(/\\/g, '/');
    const match = normalized.match(/\/projects\/([^/]+)\//);
    return match ? match[1] : '';
}

// ---- Adapter ---------------------------------------------------------------

export class IgnitionDebugAdapter implements vscode.DebugAdapter {
    private static readonly THREAD_ID = 1;

    private seq = 1;
    private sessionId: string | null = null;

    /** Resolved once launch completes, so configurationDone can wait on it. */
    private launchDone: Promise<void> | null = null;
    private launchDoneResolve: (() => void) | null = null;

    /** Breakpoints staged before a session exists. */
    private stagedBreakpoints = new Map<
        string,
        Array<{ line: number; condition?: string; hitCount?: number }>
    >();

    /** Bound callback so we can unsubscribe later. */
    private readonly onDebugEventBound = this.onDebugEvent.bind(this);

    private readonly _onDidSendMessage = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
    readonly onDidSendMessage: vscode.Event<vscode.DebugProtocolMessage> =
        this._onDidSendMessage.event;

    constructor(
        private readonly connection: ConnectionManager,
        private readonly discovery?: DiscoveryService,
    ) {
        this.connection.onDebugEvent(this.onDebugEventBound);
    }

    // ---------- DAP entry points -------------------------------------------

    handleMessage(message: vscode.DebugProtocolMessage): void {
        const req = message as DapRequest;
        if (req.type === 'request') {
            this.dispatch(req.command, req.seq, req.arguments ?? {});
        }
    }

    dispose(): void {
        this.connection.offDebugEvent(this.onDebugEventBound);
        this._onDidSendMessage.dispose();
        void this.stopSession();
    }

    // ---------- Dispatcher --------------------------------------------------

    private dispatch(command: string, seq: number, args: unknown): void {
        switch (command) {
            case 'initialize':
                this.handleInitialize(seq, args as InitializeArgs);
                break;
            case 'launch':
                void this.handleLaunch(seq, args as LaunchArgs);
                break;
            case 'disconnect':
                this.handleDisconnect(seq);
                break;
            case 'terminate':
                this.handleTerminate(seq);
                break;
            case 'setBreakpoints':
                void this.handleSetBreakpoints(seq, args as SetBreakpointsArgs);
                break;
            case 'setExceptionBreakpoints':
                this.sendOk(seq, 'setExceptionBreakpoints');
                break;
            case 'configurationDone':
                void this.handleConfigurationDone(seq);
                break;
            case 'threads':
                this.handleThreads(seq);
                break;
            case 'stackTrace':
                void this.handleStackTrace(seq, args as StackTraceArgs);
                break;
            case 'scopes':
                void this.handleScopes(seq, args as ScopesArgs);
                break;
            case 'variables':
                void this.handleVariables(seq, args as VariablesArgs);
                break;
            case 'continue':
                void this.handleStep(seq, 'continue', 'debug.continue', args as ContinueArgs);
                break;
            case 'next':
                void this.handleStep(seq, 'next', 'debug.stepOver', args as StepArgs);
                break;
            case 'stepIn':
                void this.handleStep(seq, 'stepIn', 'debug.stepInto', args as StepArgs);
                break;
            case 'stepOut':
                void this.handleStep(seq, 'stepOut', 'debug.stepOut', args as StepArgs);
                break;
            case 'pause':
                void this.handleStep(seq, 'pause', 'debug.pause', args as StepArgs);
                break;
            case 'evaluate':
                void this.handleEvaluate(seq, args as EvaluateArgs);
                break;
            case 'source':
                this.sendError(seq, 'source', 'Source not available');
                break;
            default:
                this.sendError(seq, command, `Unsupported command: ${command}`);
        }
    }

    // ---------- Handlers ----------------------------------------------------

    private handleInitialize(seq: number, _args: InitializeArgs): void {
        this.sendOk(seq, 'initialize', {
            supportsConfigurationDoneRequest: true,
            supportsConditionalBreakpoints: true,
            supportsHitConditionalBreakpoints: true,
            supportsEvaluateForHovers: true,
            supportsTerminateRequest: true,
            supportTerminateDebuggee: true,
        });
        this.sendEvent('initialized');
    }

    private async tryAutoConnect(): Promise<boolean> {
        if (!this.discovery) {
            return false;
        }
        try {
            const instances = await this.discovery.getAllInstances();
            if (instances.length === 0) {
                return false;
            }
            // Prefer gateway instances; fall back to first available
            const target = instances.find((i) => i.scope === 'gateway') ?? instances[0];
            await this.connection.connect(target);
            return this.connection.getConnectionState() === ConnectionState.CONNECTED;
        } catch {
            return false;
        }
    }

    private async handleLaunch(seq: number, args: LaunchArgs): Promise<void> {
        this.launchDone = new Promise<void>((res) => {
            this.launchDoneResolve = res;
        });

        if (this.connection.getConnectionState() !== ConnectionState.CONNECTED) {
            // Try to auto-connect using discovery
            const connected = await this.tryAutoConnect();
            if (!connected) {
                this.sendError(seq, 'launch', 'Not connected to Ignition. Install the Ignition Debugger module and ensure the gateway or Designer is running.');
                this.sendEvent('terminated');
                this.launchDoneResolve?.();
                return;
            }
        }

        try {
            const fileUri = vscode.Uri.file(args.program);
            const fileBytes = await vscode.workspace.fs.readFile(fileUri);
            const code = Buffer.from(fileBytes).toString('utf8');

            const result = await this.connection.sendRequest<{
                success: boolean;
                sessionId?: string;
                error?: string;
            }>('debug.startSession', {
                code,
                filePath: args.program,
                modulePath: args.program,
                projectName: extractProjectName(args.program),
            });

            if (!result.success || !result.sessionId) {
                this.sendError(seq, 'launch', result.error ?? 'Failed to start debug session');
                this.sendEvent('terminated');
                this.launchDoneResolve?.();
                return;
            }

            this.sessionId = result.sessionId;
            this.launchDoneResolve?.();
            this.sendOk(seq, 'launch');

            if (args.stopOnEntry) {
                this.sendEvent('stopped', {
                    reason: 'entry',
                    threadId: IgnitionDebugAdapter.THREAD_ID,
                });
            }
        } catch (err) {
            const msg = err instanceof Error ? err.message : String(err);
            this.sendError(seq, 'launch', `Launch failed: ${msg}`);
            this.sendEvent('terminated');
            this.launchDoneResolve?.();
        }
    }

    private handleDisconnect(seq: number): void {
        void this.stopSession();
        this.sendOk(seq, 'disconnect');
    }

    private handleTerminate(seq: number): void {
        void this.stopSession();
        this.sendOk(seq, 'terminate');
        this.sendEvent('terminated');
    }

    private async handleSetBreakpoints(seq: number, args: SetBreakpointsArgs): Promise<void> {
        const filePath = args.source.path ?? '';
        const clientBps = args.breakpoints ?? [];

        if (!this.sessionId) {
            // Stage breakpoints for later
            this.stagedBreakpoints.set(
                filePath,
                clientBps.map((bp) => ({
                    line: bp.line,
                    condition: bp.condition,
                    hitCount: bp.hitCondition ? parseInt(bp.hitCondition, 10) : undefined,
                }))
            );
            const bps = clientBps.map((bp, i) => ({
                id: i + 1,
                verified: false,
                line: bp.line,
                message: 'Breakpoint will be set when debug session starts',
            }));
            this.sendOk(seq, 'setBreakpoints', { breakpoints: bps });
            return;
        }

        try {
            const result = await this.connection.sendRequest<{
                breakpoints: Array<{ id: number; verified: boolean; line: number; message?: string }>;
            }>('debug.setBreakpoints', {
                sessionId: this.sessionId,
                filePath,
                breakpoints: clientBps.map((bp) => ({
                    line: bp.line,
                    condition: bp.condition,
                    hitCount: bp.hitCondition ? parseInt(bp.hitCondition, 10) : undefined,
                })),
            });
            this.sendOk(seq, 'setBreakpoints', { breakpoints: result.breakpoints });
        } catch (err) {
            const msg = err instanceof Error ? err.message : String(err);
            const bps = clientBps.map((bp, i) => ({
                id: i + 1,
                verified: false,
                line: bp.line,
                message: `Error: ${msg}`,
            }));
            this.sendOk(seq, 'setBreakpoints', { breakpoints: bps });
        }
    }

    private async handleConfigurationDone(seq: number): Promise<void> {
        // Wait for launch to complete so we have a sessionId
        if (this.launchDone) {
            await this.launchDone;
        }

        if (this.sessionId) {
            // Send any staged breakpoints
            for (const [filePath, bps] of this.stagedBreakpoints) {
                if (bps.length === 0) continue;
                await this.connection
                    .sendRequest('debug.setBreakpoints', {
                        sessionId: this.sessionId,
                        filePath,
                        breakpoints: bps,
                    })
                    .catch(() => undefined);
            }
            this.stagedBreakpoints.clear();

            // Tell the module to start executing
            await this.connection
                .sendRequest('debug.run', { sessionId: this.sessionId })
                .catch((err: unknown) => {
                    const msg = err instanceof Error ? err.message : String(err);
                    this.sendError(seq, 'configurationDone', `Failed to run: ${msg}`);
                });
        }

        this.sendOk(seq, 'configurationDone');
    }

    private handleThreads(seq: number): void {
        this.sendOk(seq, 'threads', {
            threads: [{ id: IgnitionDebugAdapter.THREAD_ID, name: 'MainThread' }],
        });
    }

    private async handleStackTrace(seq: number, args: StackTraceArgs): Promise<void> {
        if (!this.sessionId) {
            this.sendOk(seq, 'stackTrace', { stackFrames: [], totalFrames: 0 });
            return;
        }
        try {
            const result = await this.connection.sendRequest<{
                stackFrames: Array<{
                    id: number;
                    name: string;
                    filePath: string;
                    line: number;
                    column: number;
                }>;
                totalFrames: number;
            }>('debug.getStackTrace', {
                sessionId: this.sessionId,
                threadId: args.threadId,
                startFrame: args.startFrame,
                levels: args.levels,
            });

            const stackFrames = result.stackFrames.map((f) => ({
                id: f.id,
                name: f.name,
                source: { name: f.filePath.split('/').pop() ?? f.name, path: f.filePath },
                line: f.line,
                column: f.column,
            }));
            this.sendOk(seq, 'stackTrace', { stackFrames, totalFrames: result.totalFrames });
        } catch {
            this.sendOk(seq, 'stackTrace', { stackFrames: [], totalFrames: 0 });
        }
    }

    private async handleScopes(seq: number, args: ScopesArgs): Promise<void> {
        if (!this.sessionId) {
            this.sendOk(seq, 'scopes', { scopes: [] });
            return;
        }
        try {
            const result = await this.connection.sendRequest<{
                scopes: Array<{ name: string; variablesReference: number; expensive: boolean }>;
            }>('debug.getScopes', { sessionId: this.sessionId, frameId: args.frameId });
            this.sendOk(seq, 'scopes', { scopes: result.scopes });
        } catch {
            this.sendOk(seq, 'scopes', { scopes: [] });
        }
    }

    private async handleVariables(seq: number, args: VariablesArgs): Promise<void> {
        if (!this.sessionId) {
            this.sendOk(seq, 'variables', { variables: [] });
            return;
        }
        try {
            const result = await this.connection.sendRequest<{
                variables: Array<{
                    name: string;
                    value: string;
                    type: string;
                    variablesReference: number;
                }>;
            }>('debug.getVariables', {
                sessionId: this.sessionId,
                variablesReference: args.variablesReference,
                start: args.start,
                count: args.count,
            });
            this.sendOk(seq, 'variables', { variables: result.variables });
        } catch {
            this.sendOk(seq, 'variables', { variables: [] });
        }
    }

    private async handleStep(
        seq: number,
        command: string,
        method: string,
        _args: ContinueArgs | StepArgs
    ): Promise<void> {
        if (!this.sessionId) {
            this.sendError(seq, command, 'No active debug session');
            return;
        }
        try {
            await this.connection.sendRequest(method, {
                sessionId: this.sessionId,
                threadId: IgnitionDebugAdapter.THREAD_ID,
            });
            this.sendOk(seq, command);
        } catch (err) {
            const msg = err instanceof Error ? err.message : String(err);
            this.sendError(seq, command, msg);
        }
    }

    private async handleEvaluate(seq: number, args: EvaluateArgs): Promise<void> {
        if (!this.sessionId) {
            this.sendError(seq, 'evaluate', 'No active debug session');
            return;
        }
        try {
            const result = await this.connection.sendRequest<{
                result: string;
                type: string;
                variablesReference: number;
            }>('debug.evaluate', {
                sessionId: this.sessionId,
                expression: args.expression,
                frameId: args.frameId,
                context: args.context,
            });
            this.sendOk(seq, 'evaluate', result);
        } catch (err) {
            const msg = err instanceof Error ? err.message : String(err);
            this.sendError(seq, 'evaluate', msg);
        }
    }

    // ---------- Debug event routing -----------------------------------------

    private onDebugEvent(event: DebugEventData): void {
        const handlers: Record<string, () => void> = {
            stopped: () =>
                this.sendEvent('stopped', {
                    ...(event.body as object),
                    threadId: IgnitionDebugAdapter.THREAD_ID,
                    allThreadsStopped: true,
                }),
            terminated: () => {
                this.sendEvent('terminated');
                this.sessionId = null;
            },
            exited: () => this.sendEvent('exited', event.body),
            output: () => this.sendEvent('output', event.body),
            breakpoint: () => this.sendEvent('breakpoint', event.body),
        };

        handlers[event.event]?.();
    }

    // ---------- Session management ------------------------------------------

    private async stopSession(): Promise<void> {
        if (this.sessionId) {
            await this.connection
                .sendRequest('debug.stopSession', { sessionId: this.sessionId })
                .catch(() => undefined);
            this.sessionId = null;
        }
        this.stagedBreakpoints.clear();
    }

    // ---------- DAP message helpers -----------------------------------------

    private sendOk(requestSeq: number, command: string, body?: unknown): void {
        const response: DapResponse = {
            type: 'response',
            seq: this.seq++,
            request_seq: requestSeq,
            success: true,
            command,
            body,
        };
        this._onDidSendMessage.fire(response as vscode.DebugProtocolMessage);
    }

    private sendError(requestSeq: number, command: string, message: string): void {
        const response: DapResponse = {
            type: 'response',
            seq: this.seq++,
            request_seq: requestSeq,
            success: false,
            command,
            message,
        };
        this._onDidSendMessage.fire(response as vscode.DebugProtocolMessage);
    }

    private sendEvent(event: string, body?: unknown): void {
        const msg: DapEvent = {
            type: 'event',
            seq: this.seq++,
            event,
            body,
        };
        this._onDidSendMessage.fire(msg as vscode.DebugProtocolMessage);
    }
}
