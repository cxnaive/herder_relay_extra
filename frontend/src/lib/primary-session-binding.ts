import type { Agent } from './types';

export function bindingReadOnlyNotice(agent: Pick<Agent, 'binding_state'>): string {
  if (agent.binding_state === 'history_only') return 'Associated history only. The current primary session is unconfirmed; input and approvals are disabled.';
  if (agent.binding_state === 'pinned_history') return 'Manually selected history only. Input and approvals are disabled.';
  if (agent.binding_state && agent.binding_state !== 'verified') return 'Primary session is unverified. Input and approvals are disabled.';
  return 'Reader access is read only. Use a controller device to send input or answer prompts.';
}
