import type { Agent } from './types';

type BindingAgent = Pick<Agent, 'binding_state' | 'binding_reason'> & { agent?: string };

export function bindingReadOnlyNotice(agent: BindingAgent): string {
  const reader = 'Reader access is read only. Use a controller device to send input or answer prompts.';
  if (agent.agent && agent.agent !== 'codex') return reader;
  if (agent.binding_reason?.startsWith('standalone_')) return 'Standalone Codex identity is unverified: writer locks or metadata are unavailable, conflicting, or name multiple roots. Old hook reports cannot select a root. Check the computer terminal.';
  if (['runtime_metadata_conflict', 'process_project_conflict'].includes(agent.binding_reason || '')) return 'Codex metadata conflict. Check the terminal and workspace.';
  if (agent.binding_reason === 'runtime_changed') return 'Terminal or session changed. Refreshing; old requests expired.';
  if (agent.binding_reason === 'runtime_platform_unsupported') return 'Codex runtime verification is unsupported on this platform.';
  if (agent.binding_state && agent.binding_state !== 'verified') return 'Codex primary session is unverified. Check the Herdr hook or try --no-daemon; a fix is not guaranteed. Cached history is not current.';
  return reader;
}
