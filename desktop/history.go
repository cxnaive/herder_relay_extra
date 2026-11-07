package main

import (
	"context"
	"encoding/json"
	"time"

	"github.com/cxnaive/herdr-desktop/internal/relay"
)

type cachedConversation struct {
	Entries  []entry
	Revision string
	Saved    time.Time
}

func (s *storage) cachedHistory(key string) cachedConversation {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state.History[key]
}
func (s *storage) saveHistory(key string, entries []entry, revision string) error {
	if len(entries) > 300 {
		entries = entries[len(entries)-300:]
	}
	for len(entries) > 0 {
		b, _ := json.Marshal(entries)
		if len(b) <= 1024*1024 {
			break
		}
		entries = entries[1:]
	}
	if len(entries) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	old := s.state.History
	next := map[string]cachedConversation{}
	for k, v := range old {
		next[k] = v
	}
	next[key] = cachedConversation{append([]entry{}, entries...), revision, time.Now()}
	for len(next) > 8 {
		oldest := ""
		var at time.Time
		for k, v := range next {
			if oldest == "" || v.Saved.Before(at) {
				oldest = k
				at = v.Saved
			}
		}
		delete(next, oldest)
	}
	s.state.History = next
	if err := s.vault.Save(s.state); err != nil {
		s.state.History = old
		return err
	}
	return nil
}
func (a *application) cacheHistory(m *machine, c *conversation) {
	if a.store == nil || time.Since(c.lastCached) < 15*time.Second {
		return
	}
	c.lastCached = time.Now()
	if err := a.store.saveHistory(m.config.ID+":"+c.agent.Identity(), c.entries, c.revision); err != nil {
		a.status = "未能保存最近对话：" + err.Error()
	}
}
func mergeHistory(old, incoming []entry, older bool) []entry {
	if older {
		seen := map[string]bool{}
		out := append([]entry{}, incoming...)
		for _, e := range incoming {
			seen[e.ID] = true
		}
		for _, e := range old {
			if !seen[e.ID] {
				out = append(out, e)
			}
		}
		return out
	}
	// Insert recovered messages before their next known anchor.
	positions := map[string]int{}
	for i, e := range old {
		positions[e.ID] = i
	}
	index := 0
	out := []entry{}
	first := -1
	for _, e := range incoming {
		if i, ok := positions[e.ID]; ok {
			first = i
			break
		}
	}
	if first < 0 {
		out = append(out, old...)
		index = len(old)
	} else {
		out = append(out, old[:first]...)
		index = first
	}
	added := map[string]bool{}
	for _, e := range out {
		added[e.ID] = true
	}
	for _, e := range incoming {
		if pos, ok := positions[e.ID]; ok && pos >= index {
			for _, v := range old[index:pos] {
				if !added[v.ID] {
					out = append(out, v)
					added[v.ID] = true
				}
			}
			index = pos + 1
		}
		if !added[e.ID] {
			out = append(out, e)
			added[e.ID] = true
		}
	}
	for _, e := range old[index:] {
		if !added[e.ID] {
			out = append(out, e)
		}
	}
	return out
}
func (c *conversation) applyBatch(batch relay.Message) bool {
	kind := relay.Text(batch, "kind")
	seq := relay.Number(batch, "sequence")
	if c.subscription == "" || relay.Text(batch, "subscription_id") != c.subscription || relay.Text(batch, "source_revision") != c.revision {
		return false
	}
	if kind == "heartbeat" {
		return seq == c.sequence
	}
	if kind != "batch" || seq != c.sequence+1 {
		return false
	}
	remove := relay.Decode[[]string](batch["remove"])
	removed := map[string]bool{}
	for _, id := range remove {
		removed[id] = true
	}
	out := []entry{}
	index := map[string]int{}
	for _, e := range c.entries {
		if !removed[e.ID] {
			index[e.ID] = len(out)
			out = append(out, e)
		}
	}
	b, err := json.Marshal(batch["entries"])
	var incoming []entry
	if err != nil || json.Unmarshal(b, &incoming) != nil {
		return false
	}
	for _, e := range incoming {
		if e.ID == "" {
			return false
		}
		if i, ok := index[e.ID]; ok {
			out[i] = e
		} else {
			index[e.ID] = len(out)
			out = append(out, e)
		}
	}
	if len(out) > 2000 {
		return false
	}
	c.entries = out
	c.sequence = seq
	return true
}
func (a *application) syncConversation() {
	m, c := a.selected()
	if m == nil || c == nil || m.session == nil || c.loading || c.updating || !c.agent.CanChat() {
		return
	}
	if c.subscription == "" {
		if a.refreshCount%3 == 0 {
			a.loadHistory(false)
		}
		return
	}
	s, agent, epoch, subscription := m.session, c.agent, m.epoch, c.subscription
	request := agent.Request("get_conversation_updates")
	request["source_revision"] = c.revision
	request["subscription_id"] = subscription
	request["after"] = c.sequence
	ctx, cancel := context.WithTimeout(a.ctx, 20*time.Second)
	c.updateCancel = cancel
	c.updating = true
	go func() {
		defer cancel()
		batch, err := s.Request(ctx, request)
		a.update(func() {
			c.updating = false
			c.updateCancel = nil
			if _, ok := exactAgent(m, agent); !ok || m.epoch != epoch || c.subscription != subscription {
				return
			}
			if err != nil || !c.applyBatch(batch) {
				a.stopConversationUpdates(m, c)
				c.replaceHistory = true
				return
			}
			if relay.Text(batch, "kind") != "heartbeat" {
				a.cacheHistory(m, c)
			}
		})
	}()
}

func (a *application) stopConversationUpdates(m *machine, c *conversation) {
	if c.updateCancel != nil {
		c.updateCancel()
	}
	id := c.subscription
	c.subscription = ""
	if id == "" || m.session == nil || a.demo {
		return
	}
	req := c.agent.Request("unwatch_conversation")
	req["subscription_id"] = id
	req["protocol"] = 3
	s := m.session
	go s.Send(req)
}
