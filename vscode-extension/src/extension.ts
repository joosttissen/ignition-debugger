/**
 * VS Code extension entry point for the Ignition Debugger.
 *
 * Registers the debug adapter factory and commands for connecting/
 * disconnecting from Ignition Designer instances.
 */

import * as vscode from 'vscode';
import { IgnitionDebugAdapter } from './debug/IgnitionDebugAdapter';
import { ConnectionManager, ConnectionState } from './services/ConnectionManager';
import { DesignerInstance, DiscoveryService } from './services/DiscoveryService';

// Shared connection manager (one active Designer connection at a time)
let connectionManager: ConnectionManager | null = null;
let statusBarItem: vscode.StatusBarItem | null = null;

export function activate(context: vscode.ExtensionContext): void {
    connectionManager = new ConnectionManager();
    const discovery = new DiscoveryService(
        vscode.workspace.getConfiguration('ignition-debugger').get<string>('registryPath') || undefined
    );

    // Status bar
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 10);
    statusBarItem.command = 'ignition-debugger.connectToDesigner';
    updateStatusBar(ConnectionState.DISCONNECTED, null);
    statusBarItem.show();
    context.subscriptions.push(statusBarItem);

    connectionManager.onConnectionStateChanged((state, designer) => {
        updateStatusBar(state, designer);
    });

    // Debug adapter factory
    const factory = new IgnitionDebugAdapterFactory(connectionManager);
    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory('ignition', factory)
    );

    // Commands
    context.subscriptions.push(
        vscode.commands.registerCommand('ignition-debugger.connectToDesigner', async () => {
            await cmdConnect(discovery, connectionManager!);
        }),
        vscode.commands.registerCommand('ignition-debugger.disconnectFromDesigner', async () => {
            await connectionManager?.disconnect();
            void vscode.window.showInformationMessage('Disconnected from Ignition Designer');
        }),
        vscode.commands.registerCommand('ignition-debugger.refreshDesigners', async () => {
            await cmdConnect(discovery, connectionManager!);
        })
    );

    context.subscriptions.push({
        dispose: () => {
            connectionManager?.dispose();
        },
    });
}

export function deactivate(): void {
    connectionManager?.dispose();
}

// ---- Debug adapter factory ------------------------------------------------

class IgnitionDebugAdapterFactory implements vscode.DebugAdapterDescriptorFactory {
    constructor(private readonly connection: ConnectionManager) {}

    createDebugAdapterDescriptor(
        _session: vscode.DebugSession
    ): vscode.DebugAdapterDescriptor {
        return new vscode.DebugAdapterInlineImplementation(
            new IgnitionDebugAdapter(this.connection)
        );
    }
}

// ---- Commands --------------------------------------------------------------

async function cmdConnect(
    discovery: DiscoveryService,
    connection: ConnectionManager
): Promise<void> {
    const designers = await discovery.getDesigners();

    if (designers.length === 0) {
        void vscode.window.showWarningMessage(
            'No Ignition Designer instances found. ' +
            'Make sure the Ignition Debugger module is installed and a Designer is running.'
        );
        return;
    }

    let target: DesignerInstance;

    if (designers.length === 1) {
        target = designers[0];
    } else {
        const items = designers.map((d) => ({
            label: `$(window) ${d.project.name}`,
            description: `${d.gateway.host}:${d.gateway.port}`,
            detail: `PID: ${d.pid} | User: ${d.user.username} | Port: ${d.port}`,
            designer: d,
        }));

        const picked = await vscode.window.showQuickPick(items, {
            placeHolder: 'Select an Ignition Designer to connect to',
            title: 'Ignition Debugger – Connect',
        });

        if (!picked) {
            return;
        }
        target = picked.designer;
    }

    try {
        const requestTimeoutMs = vscode.workspace
            .getConfiguration('ignition-debugger')
            .get<number>('requestTimeoutMs') ?? 30_000;

        await connection.connect(target, requestTimeoutMs);
        void vscode.window.showInformationMessage(
            `Connected to Designer: ${target.project.name} (${target.gateway.host}:${target.gateway.port})`
        );
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        void vscode.window.showErrorMessage(`Failed to connect to Designer: ${msg}`);
    }
}

// ---- Status bar ------------------------------------------------------------

function updateStatusBar(state: ConnectionState, designer: DesignerInstance | null): void {
    if (!statusBarItem) {
        return;
    }
    switch (state) {
        case ConnectionState.CONNECTED:
            statusBarItem.text = `$(debug-breakpoint-data) Ignition: ${designer?.project.name ?? 'Connected'}`;
            statusBarItem.tooltip = `Connected to ${designer?.gateway.host}:${designer?.gateway.port}`;
            statusBarItem.backgroundColor = undefined;
            break;
        case ConnectionState.CONNECTING:
            statusBarItem.text = '$(loading~spin) Ignition: Connecting…';
            statusBarItem.tooltip = 'Connecting to Ignition Designer';
            statusBarItem.backgroundColor = undefined;
            break;
        case ConnectionState.ERROR:
            statusBarItem.text = '$(error) Ignition: Error';
            statusBarItem.tooltip = 'Connection error – click to retry';
            statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
            break;
        default:
            statusBarItem.text = '$(plug) Ignition: Disconnected';
            statusBarItem.tooltip = 'Click to connect to Ignition Designer';
            statusBarItem.backgroundColor = undefined;
    }
}
