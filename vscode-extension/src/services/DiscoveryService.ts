/**
 * Discovery service for finding running Ignition Designer instances.
 *
 * The Ignition Debugger module writes registry files to
 * ~/.ignition/debugger/designers/designer-{pid}.json when a Designer starts.
 * This service scans that directory to discover available connections.
 */

import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';

/** Represents a discovered Ignition Designer instance */
export interface DesignerInstance {
    readonly pid: number;
    readonly port: number;
    readonly startTime: string;
    readonly gateway: {
        readonly host: string;
        readonly port: number;
        readonly ssl: boolean;
        readonly name: string;
    };
    readonly project: {
        readonly name: string;
        readonly title: string;
    };
    readonly user: {
        readonly username: string;
    };
    readonly designerVersion: string;
    readonly moduleVersion: string;
    readonly secret: string;
    readonly registryFilePath: string;
}

export class DiscoveryService {
    private static readonly DEFAULT_REGISTRY_DIR = path.join(
        os.homedir(),
        '.ignition',
        'debugger',
        'designers'
    );

    private readonly registryDir: string;

    constructor(registryDirOverride?: string) {
        this.registryDir = registryDirOverride ?? DiscoveryService.DEFAULT_REGISTRY_DIR;
    }

    /** Scan the registry directory and return all valid Designer instances */
    async getDesigners(): Promise<DesignerInstance[]> {
        let files: string[];
        try {
            files = await fs.readdir(this.registryDir);
        } catch {
            // Directory does not exist yet – no designers running
            return [];
        }

        const registryFiles = files.filter(
            (f) => f.startsWith('designer-') && f.endsWith('.json')
        );

        const results: DesignerInstance[] = [];
        for (const file of registryFiles) {
            const filePath = path.join(this.registryDir, file);
            const instance = await this.readRegistryFile(filePath);
            if (instance) {
                results.push(instance);
            }
        }
        return results;
    }

    private async readRegistryFile(filePath: string): Promise<DesignerInstance | null> {
        try {
            const content = await fs.readFile(filePath, 'utf-8');
            const data = JSON.parse(content) as Partial<DesignerInstance>;

            if (!data.pid || !data.port || !data.secret) {
                return null;
            }

            if (!this.isProcessAlive(data.pid)) {
                // Stale file – clean it up
                await fs.unlink(filePath).catch(() => undefined);
                return null;
            }

            return {
                pid: data.pid,
                port: data.port,
                startTime: data.startTime ?? new Date().toISOString(),
                gateway: data.gateway ?? { host: 'localhost', port: 8088, ssl: false, name: 'local' },
                project: data.project ?? { name: 'Unknown', title: 'Unknown' },
                user: data.user ?? { username: 'unknown' },
                designerVersion: data.designerVersion ?? 'unknown',
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
