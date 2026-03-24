/**
 * VS Code extension entry point for the Ignition Debugger.
 *
 * Registers the debug adapter factory and commands for connecting/
 * disconnecting from Ignition Designer and Gateway instances.
 */

import * as vscode from 'vscode';
import { IgnitionDebugAdapter } from './debug/IgnitionDebugAdapter';
import { ConnectionManager, ConnectionState } from './services/ConnectionManager';
import { DesignerInstance, DiscoveryService } from './services/DiscoveryService';

// Shared connection manager (one active connection at a time)
let connectionManager: ConnectionManager | null = null;
let statusBarItem: vscode.StatusBarItem | null = null;

export function activate(context: vscode.ExtensionContext): void {
    connectionManager = new ConnectionManager();
    const config = vscode.workspace.getConfiguration('ignition-debugger');
    const discovery = new DiscoveryService(
        config.get<string>('registryPath') || undefined,
        config.get<string>('gatewayRegistryPath') || undefined
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
    const factory = new IgnitionDebugAdapterFactory(connectionManager, discovery);
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
            void vscode.window.showInformationMessage('Disconnected from Ignition');
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
    constructor(
        private readonly connection: ConnectionManager,
        private readonly discovery: DiscoveryService,
    ) {}

    createDebugAdapterDescriptor(
        _session: vscode.DebugSession
    ): vscode.DebugAdapterDescriptor {
        return new vscode.DebugAdapterInlineImplementation(
            new IgnitionDebugAdapter(this.connection, this.discovery)
        );
    }
}

// ---- Commands --------------------------------------------------------------

async function cmdConnect(
    discovery: DiscoveryService,
    connection: ConnectionManager
): Promise<void> {
    const instances = await discovery.getAllInstances();

    if (instances.length === 0) {
        void vscode.window.showWarningMessage(
            'No Ignition Designer or Gateway instances found. ' +
            'Make sure the Ignition Debugger module is installed and a Designer or Gateway is running.'
        );
        return;
    }

    let target: DesignerInstance;

    if (instances.length === 1) {
        target = instances[0];
    } else {
        const items = instances.map((d) => ({
            label: d.scope === 'gateway'
                ? `$(server) Gateway: ${d.gateway.name}`
                : `$(window) Designer: ${d.project?.name ?? 'Unknown'}`,
            description: `${d.gateway.host}:${d.gateway.port}`,
            detail: d.scope === 'gateway'
                ? `PID: ${d.pid} | Port: ${d.port}`
                : `PID: ${d.pid} | User: ${d.user?.username ?? 'unknown'} | Port: ${d.port}`,
            designer: d,
        }));

        const picked = await vscode.window.showQuickPick(items, {
            placeHolder: 'Select an Ignition instance to connect to',
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
        const label = target.scope === 'gateway'
            ? `Gateway: ${target.gateway.name} (${target.gateway.host}:${target.gateway.port})`
            : `Designer: ${target.project?.name ?? 'Unknown'} (${target.gateway.host}:${target.gateway.port})`;
        void vscode.window.showInformationMessage(`Connected to ${label}`);
    } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        void vscode.window.showErrorMessage(`Failed to connect to Ignition: ${msg}`);
    }
}

// ---- Status bar ------------------------------------------------------------

function updateStatusBar(state: ConnectionState, instance: DesignerInstance | null): void {
    if (!statusBarItem) {
        return;
    }
    switch (state) {
        case ConnectionState.CONNECTED: {
            const label = instance?.scope === 'gateway'
                ? `Gateway: ${instance.gateway.name}`
                : instance?.project?.name ?? 'Connected';
            statusBarItem.text = `$(debug-breakpoint-data) Ignition: ${label}`;
            statusBarItem.tooltip = `Connected to ${instance?.gateway.host}:${instance?.gateway.port}`;
            statusBarItem.backgroundColor = undefined;
            break;
        }
        case ConnectionState.CONNECTING:
            statusBarItem.text = '$(loading~spin) Ignition: Connecting…';
            statusBarItem.tooltip = 'Connecting to Ignition';
            statusBarItem.backgroundColor = undefined;
            break;
        case ConnectionState.ERROR:
            statusBarItem.text = '$(error) Ignition: Error';
            statusBarItem.tooltip = 'Connection error – click to retry';
            statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
            break;
        default:
            statusBarItem.text = '$(plug) Ignition: Disconnected';
            statusBarItem.tooltip = 'Click to connect to Ignition Designer or Gateway';
            statusBarItem.backgroundColor = undefined;
    }
}
