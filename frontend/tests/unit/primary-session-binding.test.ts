import { describe, expect, it } from 'vitest';
import { bindingReadOnlyNotice } from '../../src/lib/primary-session-binding';
import { normalizeFrontendTargetRef, targetRefForAgent, targetRefMatchesAgent, targetIdentityMatchesAgent, targetStoreKey } from '../../src/lib/resource-id';

const agent = { relay_id: 'computer', raw_pane_id: 'pane', server_session_id: 'primary', terminal_id: 'terminal', generation: 3, agent_session_id: 'thread', binding_epoch: 1 };

describe('primary session lease', () => {
  it('keeps history identity but invalidates old responses after recovery', () => {
    const old = targetRefForAgent(agent)!;
    const recovered = { ...agent, binding_epoch: 3 };
    expect(old.binding_epoch).toBe(1);
    expect(targetRefMatchesAgent(old, recovered)).toBe(false);
    expect(targetIdentityMatchesAgent(old, recovered)).toBe(true);
    expect(targetStoreKey(old)).toBe(targetStoreKey(targetRefForAgent(recovered)!));
    expect(targetIdentityMatchesAgent(old, { ...recovered, agent_session_id: 'other' })).toBe(false);
  });
  it('does not silently discard malformed or missing lease identity', () => {
    const target = targetRefForAgent(agent)!;
    expect(normalizeFrontendTargetRef({ ...target, binding_epoch: -1 })).toBeNull();
    expect(normalizeFrontendTargetRef({ ...target, binding_epoch: 2 ** 53 })).toBeNull();
    expect(targetRefMatchesAgent({ ...target, binding_epoch: undefined }, agent)).toBe(false);
  });
});

describe('read-only session explanation', () => {
  it('never tells a controller to change devices to operate unconfirmed history', () => {
    expect(bindingReadOnlyNotice({ binding_state: 'history_only' })).toContain('Associated history only');
    expect(bindingReadOnlyNotice({ binding_state: 'history_only' })).toContain('unconfirmed');
    for (const state of ['history_only', 'pinned_history', 'ambiguous', 'stale']) {
      expect(bindingReadOnlyNotice({ binding_state: state })).not.toContain('controller device');
    }
    expect(bindingReadOnlyNotice({ binding_state: 'verified' })).toContain('controller device');
  });
});
