import { forbidden, ToolError, type AllowedCommand, type RunResult } from '@arthadhruva/mcp-kit';

/**
 * Azure, read-only, and only what it takes to learn a VM's power state. Nothing is blocklisted: a command line is
 * allowed only if it is, argument for argument, one of the entries built here, and everything else (`az vm
 * deallocate`, `az vm start`, `az group delete`, `az deployment ...`, even a read-only command that is not listed) is
 * Forbidden before a process is spawned. The resource group and VM name come from configuration and are checked
 * against a strict pattern, so they cannot carry a second option into the command.
 */
export const NAME = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,89}$/;

export const POWER_STATE_ID = 'az-vm-power-state';

export interface Runner {
  run(id: string, options?: { signal?: AbortSignal; timeoutMs?: number }): Promise<RunResult>;
}

export interface AzTarget {
  resourceGroup: string;
  vm: string;
}

/** The argument vector of the one command this server runs. */
export function powerStateArgs(t: AzTarget): string[] {
  return ['vm', 'get-instance-view', '--resource-group', t.resourceGroup, '--name', t.vm, '--query', 'instanceView.statuses', '--output', 'json'];
}

/** The runner's allowlist entry for it. The executable is `az` (a .cmd on Windows, which the kit's runner handles). */
export function allowlist(t: AzTarget, windows = process.platform === 'win32'): AllowedCommand[] {
  return [{ id: POWER_STATE_ID, command: windows ? 'az.cmd' : 'az', args: powerStateArgs(t), timeoutMs: 30_000 }];
}

export function validTarget(t: AzTarget): AzTarget {
  for (const [what, v] of [['resource group', t.resourceGroup], ['VM name', t.vm]] as const) {
    if (!NAME.test(v)) throw new Error(`The ${what} must be letters, digits, dot, dash or underscore (got an invalid value).`);
  }
  return t;
}

const MUTATING = /^(create|delete|deallocate|start|stop|restart|update|set|add|remove|redeploy|reimage|resize|run-command|deployment|group|role|identity|extension|disk|nic|network)$/i;

export class Az {
  private readonly allowedKey: string;

  constructor(private readonly runner: Runner, private readonly target: AzTarget) {
    this.allowedKey = JSON.stringify(powerStateArgs(validTarget(target)));
  }

  /**
   * Runs an `az` command line if and only if it is on the allowlist. Anything else is refused before spawning, with a
   * message that says whether it would have changed something.
   */
  async exec(argv: readonly string[], signal?: AbortSignal): Promise<RunResult> {
    if (JSON.stringify(argv) !== this.allowedKey) {
      const verb = argv.find((a) => MUTATING.test(a));
      throw forbidden(verb
        ? `az ${argv.slice(0, 3).join(' ')} would change Azure resources ("${verb}"), and this server only reads a VM's power state. Nothing was run.`
        : `That az command is not on this server's allowlist (only the VM power-state read is). Nothing was run.`);
    }
    return this.runner.run(POWER_STATE_ID, { ...(signal ? { signal } : {}) });
  }

  /** The VM's power state (running, starting, stopping, stopped, deallocating, deallocated), or null when az cannot say. */
  async powerState(signal?: AbortSignal): Promise<{ state: string | null; reason?: string }> {
    let result: RunResult;
    try {
      result = await this.exec(powerStateArgs(this.target), signal);
    } catch (e) {
      if (e instanceof ToolError) throw e;
      return { state: null, reason: 'az could not be started' };
    }
    if (result.timedOut) return { state: null, reason: 'az did not answer in time' };
    if (result.code === 127) return { state: null, reason: 'the az command-line tool is not installed' };
    if (result.code !== 0) return { state: null, reason: 'az failed; it may not be signed in (az login)' };
    try {
      const statuses = JSON.parse(result.stdout) as Array<{ code?: string }>;
      const power = statuses.find((s) => typeof s.code === 'string' && s.code.startsWith('PowerState/'));
      return power?.code ? { state: power.code.slice('PowerState/'.length) } : { state: null, reason: 'az reported no power state' };
    } catch {
      return { state: null, reason: 'az answered something unreadable' };
    }
  }
}
