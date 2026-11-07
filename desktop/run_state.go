package main

import (
	"context"
	"encoding/json"
	"fmt"
	"slices"
	"time"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo/ui"
)

type runSample struct {
	Status    string `json:"status"`
	Source    string `json:"source"`
	Attention string `json:"attention_kind"`
	ElapsedMS *int64 `json:"elapsed_ms"`
	Ticking   bool   `json:"ticking"`
	Timing    string `json:"timing"`
}
type runPresentation struct {
	sample          *runSample
	received, next  time.Time
	target          relay.Target
	pending, failed bool
}

func (r *runPresentation) accept(m relay.Message, target relay.Target, now time.Time) bool {
	data, _ := json.Marshal(m)
	var s runSample
	if json.Unmarshal(data, &s) != nil || (s.Source != "codex_runtime" && s.Source != "herdr" && s.Source != "unverified_primary") || (s.ElapsedMS != nil && (*s.ElapsedMS < 0 || *s.ElapsedMS > 10*366*24*60*60*1000)) || (s.Ticking && s.Status != "working" && s.Status != "blocked") || (s.Source == "unverified_primary" && (s.Status != "unknown" || s.Ticking || s.ElapsedMS != nil)) {
		r.failed = true
		return false
	}
	r.sample, r.target, r.received, r.failed = &s, target, now, false
	return true
}
func (r *runPresentation) display(agent relay.Agent, online, expects bool, now time.Time) (label, timer string, spinning bool) {
	if !online {
		return "连接中断", "", false
	}
	if !agent.CanChat() {
		return "主会话待确认", "", false
	}
	s := r.sample
	if s != nil && (r.target != agent.Target || now.Sub(r.received) > 15*time.Second || r.failed) {
		return "运行状态待确认", "", false
	}
	if s == nil {
		if expects {
			if r.failed {
				return "运行状态暂不可用", "", false
			}
			return "正在确认状态…", "", true
		}
		return statusLabel(agent.Status), "用时暂不可用", agent.Status == "working"
	}
	if s.Source == "unverified_primary" {
		return "主会话待确认", "", false
	}
	label = statusLabel(s.Status)
	spinning = s.Status == "working" || s.Status == "running" || s.Status == "busy"
	if s.Status == "blocked" || s.Status == "waiting" {
		if s.Attention == "approval" {
			label = "等待审批"
		} else if s.Attention == "question" || s.Attention == "chat" {
			label = "等待输入"
		}
	}
	if s.Source == "codex_runtime" && s.Timing == "turn_elapsed" && s.ElapsedMS != nil {
		ms := *s.ElapsedMS
		if s.Ticking {
			ms += max(0, now.Sub(r.received).Milliseconds())
		}
		prefix := "上轮 "
		if spinning || s.Status == "blocked" || s.Status == "waiting" {
			prefix = "本轮 "
		}
		timer = prefix + formatDuration(ms)
	} else {
		timer = "用时暂不可用"
	}
	return
}
func formatDuration(ms int64) string {
	s := max(0, ms) / 1000
	if s >= 3600 {
		return fmt.Sprintf("%02d:%02d:%02d", s/3600, s%3600/60, s%60)
	}
	return fmt.Sprintf("%02d:%02d", s/60, s%60)
}
func (a *application) refreshRun() {
	m, c := a.selected()
	if m == nil || c == nil || a.demo || m.session == nil || !m.capabilities["agent_run_state_v1"] || c.run.pending || time.Now().Before(c.run.next) {
		return
	}
	if c.run.target != c.agent.Target {
		c.run.sample = nil
	}
	c.run.pending = true
	c.run.next = time.Now().Add(5 * time.Second)
	s, agent, epoch := m.session, c.agent, m.epoch
	go func() {
		ctx, cancel := context.WithTimeout(a.ctx, 8*time.Second)
		defer cancel()
		value, err := s.Request(ctx, agent.Request("get_agent_run_state"))
		a.win.Update(func() {
			c.run.pending = false
			if epoch != m.epoch || !slices.ContainsFunc(m.agents, func(v relay.Agent) bool { return v.Target == agent.Target }) {
				return
			}
			if err != nil {
				c.run.failed = true
				return
			}
			c.run.accept(value, agent.Target, time.Now())
		})
	}()
}
func (a *application) runStatusView(c *ui.Context, m *machine, chat *conversation) {
	label, timer, spin := chat.run.display(chat.agent, m.session != nil || a.demo, m.capabilities["agent_run_state_v1"], time.Now())
	ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
		if spin {
			ui.Spinner(c).Size(13, 13)
		}
		ui.Text(c, label).FontSize(12).TextColor(c.Theme().Accent)
		if timer != "" {
			ui.Text(c, timer).FontSize(12).TextColor(c.Theme().TextMuted)
		}
	})
}
