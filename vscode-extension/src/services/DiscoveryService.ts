/**
 * Discovery service for finding running Ignition Designer and Gateway instances.
 *
 * The Ignition Debugger module writes registry files when a Designer or Gateway starts:
 * - Designer: ~/.ignition/debugger/designers/designer-{pid}.json
 * - Gateway:  ~/.ignition/debugger/gateway/gateway-{pid}.json
 *
 * This service scans both directories to discover available connections.
 */

import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

/** Represents a discovered Ignition Designer or Gateway instance */
export interface DesignerInstance {
    readonly pid: number;
    readonly port: number;
    readonly startTime: string;
    readonly scope: 'designer' | 'gateway';
    readonly gateway: {
        readonly host: string;
        readonly port: number;
        readonly ssl: boolean;
        readonly name: string;
    };
    readonly project?: {
        readonly name: string;
        readonly title: string;
    };
    readonly user?: {
        readonly username: string;
    };
    readonly designerVersion?: string;
    readonly ignitionVersion?: string;
    readonly moduleVersion: string;
    readonly secret: string;
    readonly registryFilePath: string;
}

export class DiscoveryService {
    private static readonly DEFAULT_DESIGNER_REGISTRY_DIR = path.join(
        os.homedir(),
        '.ignition',
        'debugger',
        'designers'
    );

    private static readonly DEFAULT_GATEWAY_REGISTRY_DIR = path.join(
        os.homedir(),
        '.ignition',
        'debugger',
        'gateway'
    );

    private readonly designerRegistryDir: string;
    private readonly gatewayRegistryDir: string;

    constructor(designerRegistryDirOverride?: string, gatewayRegistryDirOverride?: string) {
        this.designerRegistryDir = designerRegistryDirOverride ?? DiscoveryService.DEFAULT_DESIGNER_REGISTRY_DIR;
        this.gatewayRegistryDir = gatewayRegistryDirOverride ?? DiscoveryService.DEFAULT_GATEWAY_REGISTRY_DIR;
    }

    /** Scan both registry directories and return all valid instances (designers + gateways) */
    async getAllInstances(): Promise<DesignerInstance[]> {
        const [designers, gateways] = await Promise.all([
            this.getDesigners(),
            this.getGatewayInstances(),
        ]);
        return [...designers, ...gateways];
    }

    /** Scan the designer registry directory and return all valid Designer instances */
    async getDesigners(): Promise<DesignerInstance[]> {
        return this.scanRegistryDir(this.designerRegistryDir, 'designer-', 'designer');
    }

    /** Scan the gateway registry directory and return all valid Gateway instances */
    async getGatewayInstances(): Promise<DesignerInstance[]> {
        return this.scanRegistryDir(this.gatewayRegistryDir, 'gateway-', 'gateway');
    }

    private async scanRegistryDir(
        dir: string,
        filePrefix: string,
        defaultScope: 'designer' | 'gateway'
    ): Promise<DesignerInstance[]> {
        let files: string[];
        try {
            files = await fs.readdir(dir);
        } catch {
            // Directory does not exist yet – no instances running
            return [];
        }

        const registryFiles = files.filter(
            (f) => f.startsWith(filePrefix) && f.endsWith('.json')
        );

        const results: DesignerInstance[] = [];
        for (const file of registryFiles) {
            const filePath = path.join(dir, file);
            const instance = await this.readRegistryFile(filePath, defaultScope);
            if (instance) {
                results.push(instance);
            }
        }
        return results;
    }

    private async readRegistryFile(
        filePath: string,
        defaultScope: 'designer' | 'gateway'
    ): Promise<DesignerInstance | null> {
        try {
            const content = await fs.readFile(filePath, 'utf-8');
            const data = JSON.parse(content) as Partial<DesignerInstance> & {
                scope?: string;
                ignitionVersion?: string;
            };

            if (!data.pid || !data.port || !data.secret) {
                return null;
            }

            if (!this.isProcessAlive(data.pid) && data.scope !== 'gateway') {
                // Stale file – clean it up
                await fs.unlink(filePath).catch(() => undefined);
                return null;
            }

            const scope: 'designer' | 'gateway' =
                data.scope === 'gateway' ? 'gateway'
                : data.scope === 'designer' ? 'designer'
                : defaultScope;

            return {
                pid: data.pid,
                port: data.port,
                startTime: data.startTime ?? new Date().toISOString(),
                scope,
                gateway: data.gateway ?? { host: 'localhost', port: 8088, ssl: false, name: 'local' },
                project: data.project,
                user: data.user,
                designerVersion: data.designerVersion,
                ignitionVersion: data.ignitionVersion,
                moduleVersion: data.moduleVersion ?? 'unknown',
                secret: data.secret,
                registryFilePath: filePath,
            };
        } catch {
            return null;
        }
    }

    private isProcessAlive(pid: number): boolean {
        try {
            process.kill(pid, 0);
            return true;
        } catch {
            return false;
        }
    }
}

