package main

import (
	"context"
	"fmt"
	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"strings"
	"time"
)

type toolsPanel struct {
	kind, title, text, message, query string
	machine                           *machine
	agent                             relay.Agent
	busy                              bool
	data                              relay.Message
}

func (a *application) openTools(m *machine, agent relay.Agent, kind string) {
	titles := map[string]string{"git": "Git 更改", "commands": "Agent 指令", "diagnostics": "会话诊断", "activity": "活动记录"}
	p := &toolsPanel{kind: kind, title: titles[kind], machine: m, agent: agent, busy: true}
	a.toolsPanel = p
	if kind == "activity" {
		if m.session == nil {
			p.busy = false
			p.message = "电脑已断开"
			return
		}
		s := m.session
		go func() {
			err := s.Send(relay.Message{"type": "get_activity", "protocol": 3, "limit": 100})
			if err != nil {
				a.update(func() { p.busy = false; p.message = err.Error() })
			}
		}()
		time.AfterFunc(10*time.Second, func() {
			a.update(func() {
				if p.busy && a.toolsPanel == p {
					p.busy = false
					p.message = "活动记录尚未返回，请重新打开"
				}
			})
		})
		return
	}
	action := map[string]string{"git": "workspace_git_status", "commands": "list_slash_commands", "diagnostics": "get_session_binding_diagnostics", "activity": "get_activity"}[kind]
	a.readAgent(m, agent, action, relay.Message{"limit": 100}, func(data relay.Message, err error) {
		p.busy = false
		if err != nil {
			p.message = err.Error()
			return
		}
		p.data = data
		if kind == "diagnostics" {
			p.text = toolValue(data)
		}
	})
}
func (a *application) toolsView(c *ui.Context) {
	p := a.toolsPanel
	if p == nil {
		return
	}
	open := true
	ui.Modal(c, &open, func() {
		ui.Row(c).Children(func() {
			ui.Text(c, p.title).Bold().FontSize(19).Grow(1)
			if ui.Button(c, "关闭面板").Clicked() {
				open = false
			}
		})
		if p.busy {
			ui.Spinner(c).Size(18, 18)
		}
		if p.message != "" {
			ui.Text(c, p.message).TextColor(c.Theme().Warning)
		}
		if p.kind == "commands" {
			ui.TextInput(c, &p.query).Label("筛选指令").Placeholder("筛选指令")
		}
		ui.Scroll(c).Height(440).Gap(10).Children(func() {
			switch p.kind {
			case "git":
				if p.data["available"] == false {
					ui.Text(c, "此项目不在 Git 仓库中")
					return
				}
				ui.Text(c, "分支："+relay.Text(p.data, "branch")).FontSize(13)
				if p.data["truncated"] == true {
					ui.Text(c, "更改较多，当前仅显示部分文件。").FontSize(12).TextColor(c.Theme().Warning)
				}
				files := relay.Decode[[]relay.Message](p.data["files"])
				if len(files) == 0 && !p.busy && p.message == "" {
					ui.Text(c, "没有未提交的更改")
				}
				for _, file := range files {
					path := relay.Text(file, "path")
					if ui.Button(c.Key(path), relay.Text(file, "status")+"  "+path).FillWidth().Disabled(p.busy).Clicked() {
						p.busy = true
						a.readAgent(p.machine, p.agent, "workspace_git_diff", relay.Message{"path": path}, func(data relay.Message, err error) {
							p.busy = false
							if err != nil {
								p.message = err.Error()
							} else {
								p.text = path + "\n\n" + relay.Text(data, "diff")
							}
						})
					}
				}
			case "commands":
				for _, command := range relay.Decode[[]relay.Message](p.data["commands"]) {
					value := relay.Text(command, "command")
					description := relay.Text(command, "description")
					if !strings.Contains(strings.ToLower(value+description), strings.ToLower(p.query)) {
						continue
					}
					if ui.Button(c.Key(value), value+"  "+description).FillWidth().Clicked() {
						m, chat := a.selected()
						if m != p.machine || chat == nil || chat.agent.Target != p.agent.Target {
							p.message = "会话已切换，请重新选择指令"
						} else if !chat.pending && !chat.uncertain {
							chat.draft = value + " "
							open = false
						}
					}
				}
			case "activity":
				for i, event := range relay.Decode[[]relay.Message](p.data["activities"]) {
					ec := c.Key(fmt.Sprint(i))
					ui.Text(ec, relay.Text(event, "summary")).Selectable()
					stamp := relay.Text(event, "timestamp")
					if ms := relay.Number(event, "timestamp"); ms > 0 {
						stamp = time.UnixMilli(ms).Local().Format("01-02 15:04:05")
					}
					ui.Text(ec, stamp+" · "+relay.Text(event, "status")).FontSize(12).TextColor(c.Theme().TextMuted)
				}
			}
			if p.text != "" {
				ui.Text(c, p.text).Font("monospace").FontSize(12).Selectable()
			}
		})
		if p.text != "" && ui.Button(c, "复制内容").Clicked() {
			mygo.Clipboard.WriteText(p.text)
		}
	}).Width(840).MaxWidth(1000)
	if !open {
		a.toolsPanel = nil
	}
}
func notificationTransition(before, after relay.Agent) string {
	if before.Identity() != after.Identity() {
		return ""
	}
	if after.Status == "blocked" && (before.Status != "blocked" || before.EventID != after.EventID || before.ApprovalFingerprint != after.ApprovalFingerprint) {
		return "需要你的处理"
	}
	if (before.Status == "working" || before.Status == "running") && (after.Status == "done" || after.Status == "completed" || after.Status == "idle") {
		return "已完成本轮任务"
	}
	return ""
}
func (a *application) notifyAgentChanges(m *machine, next []relay.Agent) {
	if a.demo || a.win == nil {
		return
	}
	for _, agent := range next {
		if a.collapsed[groupKey("mute", m.config.ID, agent.Identity())] {
			continue
		}
		for _, old := range m.agents {
			if old.Identity() != agent.Identity() {
				continue
			}
			message := notificationTransition(old, agent)
			if message == "" {
				break
			}
			n := mygo.NewNotification(mygo.NotificationOptions{ID: groupKey(m.config.ID, agent.Identity()), Title: agent.Title(), Body: m.config.Name + " · " + message, Group: m.config.ID})
			n.OnClick(func() {
				a.update(func() {
					if live, ok := exactAgent(m, agent); ok {
						a.selectAgent(m, live)
						a.mode = 0
						a.win.Show()
					}
				})
			})
			go func() {
				ctx, cancel := context.WithTimeout(a.ctx, 30*time.Second)
				defer cancel()
				if err := n.ShowContext(ctx); err != nil {
					a.update(func() { a.status = "系统通知未送达，请检查通知权限" })
				}
			}()
			break
		}
	}
}
