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
    expect(bindingReadOnlyNotice({ binding_state: 'history_only' })).toContain('Codex primary session is unverified');
    expect(bindingReadOnlyNotice({ binding_state: 'history_only' })).toContain('unverified');
    for (const state of ['history_only', 'pinned_history', 'ambiguous', 'stale']) {
      expect(bindingReadOnlyNotice({ binding_state: state })).not.toContain('controller device');
    }
    expect(bindingReadOnlyNotice({ binding_state: 'verified' })).toContain('controller device');
  });
});

describe('Codex runtime-only boundary', () => {
  it('suggests no-daemon only for unresolved Codex identity, without promising a fix', () => {
    const text = bindingReadOnlyNotice({ agent: 'codex', binding_state: 'ambiguous', binding_reason: 'runtime_ambiguous_no_report' });
    expect(text).toContain('--no-daemon');
    expect(text).toContain('not guaranteed');
    expect(text).not.toMatch(/\/status|\/pwd/);
  });
  it('does not suggest enabling standalone mode when it is already enabled', () => {
    for (const binding_reason of ['standalone_runtime_unavailable', 'standalone_metadata_unavailable', 'standalone_ambiguous', 'standalone_metadata_conflict', 'standalone_ancestry_unavailable']) {
      const text = bindingReadOnlyNotice({ agent: 'codex', binding_state: 'ambiguous', binding_reason });
      expect(text).toContain('Standalone');
      expect(text).toContain('Old hook');
      expect(text).not.toContain('--no-daemon');
    }
  });
  it('leaves other providers on their normal reader notice', () => {
    for (const agent of ['claude', 'opencode', 'gemini', 'kimi', 'pi']) {
      const text = bindingReadOnlyNotice({ agent, binding_state: 'ambiguous' });
      expect(text).toContain('controller device');
      expect(text).not.toContain('--no-daemon');
    }
  });
  it('does not blame Herdr or recommend daemon changes for a metadata conflict', () => {
    const text = bindingReadOnlyNotice({ agent: 'codex', binding_state: 'conflict', binding_reason: 'runtime_metadata_conflict' });
    expect(text).toContain('metadata conflict');
    expect(text).not.toMatch(/upgrade|--no-daemon/);
  });
});
