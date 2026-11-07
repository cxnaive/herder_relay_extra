package main

import (
	"strings"

	"github.com/egoist/mygo/plugins/terminal"
	"github.com/egoist/mygo/ui"
)

func (a *application) view(c *ui.Context) {
	theme := *c.Theme()
	if theme.Dark {
		theme.Background = ui.RGB(17, 20, 23)
		theme.Surface = ui.RGB(27, 32, 37)
		theme.Accent = ui.RGB(166, 219, 201)
		theme.AccentText = ui.RGB(17, 43, 35)
		theme.Text = ui.RGB(237, 240, 243)
		theme.TextMuted = ui.RGB(165, 175, 185)
		theme.Border = ui.RGB(48, 59, 67)
	} else {
		theme.Background = ui.RGB(250, 252, 249)
		theme.Surface = ui.RGB(239, 244, 239)
		theme.Accent = ui.RGB(40, 109, 85)
		theme.AccentText = ui.RGB(255, 255, 255)
	}
	theme.FontSize = 14
	theme.Radius = 8
	c.SetTheme(&theme)
	ui.Column(c).Fill().Background(theme.Background).Children(func() {
		ui.Split(c, &a.sidebar, func() { a.sidebarView(c) }, func() {
			if a.pairing {
				a.pairView(c)
			} else {
				a.workspaceView(c)
			}
		}).Grow(1)
		if a.status != "" {
			ui.Text(c, a.status).FontSize(12).TextColor(theme.TextMuted).Padding(8, 16).FillWidth().Background(theme.Surface).Selectable()
		}
	})
	a.imageDialog(c)
}
func statusLabel(s string) string {
	switch s {
	case "working", "running", "busy", "in_progress", "started":
		return "运行中"
	case "blocked", "waiting", "needs_input", "pending":
		return "等待处理"
	case "done", "completed", "finished", "success":
		return "已完成"
	case "error", "failed":
		return "失败"
	case "interrupted":
		return "已中断"
	case "stopped", "exited", "ended":
		return "已结束"
	case "idle", "ready":
		return "空闲"
	default:
		return "状态未知"
	}
}
func (a *application) pairView(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Fill().Padding(40).Gap(18).Children(func() {
		ui.Text(c, "连接你的电脑").FontSize(26).Bold()
		ui.Text(c, "粘贴邀请链接，连接运行 Agent 的电脑。").TextColor(t.TextMuted)
		ui.Text(c, "电脑名称").FontSize(12)
		ui.TextInput(c, &a.pairName).Placeholder("例如：MacBook Pro").Label("电脑名称").MaxWidth(620)
		ui.Text(c, "配对邀请").FontSize(12)
		ui.TextInput(c, &a.pairLink).Password().Placeholder("herdr-tcp://…").Label("配对邀请").MaxWidth(620)
		ui.Text(c, "邀请链接有效期为 10 分钟。").FontSize(12).TextColor(t.TextMuted).MaxWidth(620)
		ui.Row(c).Gap(10).Children(func() {
			label := "连接电脑"
			if a.pairBusy {
				label = "正在保存…"
			}
			if ui.PrimaryButton(c, label).Disabled(a.pairBusy || a.store == nil || a.pairLink == "").Clicked() {
				a.pair()
			}
			if len(a.machines) > 0 && ui.Button(c, "返回工作台").Clicked() {
				a.pairing = false
			}
		})
		ui.Spacer(c)
		ui.Text(c, "连接信息加密保存在此设备。").FontSize(12).TextColor(t.TextMuted)
	})
}
func (a *application) workspaceView(c *ui.Context) {
	t := c.Theme()
	m, chat := a.selected()
	if chat == nil {
		ui.Column(c).Fill().Center().Gap(15).Children(func() {
			ui.Text(c, "你的 Agent 工作台").FontSize(26).Bold()
			ui.Text(c, "连接电脑，然后从左侧选择一个 Agent。").TextColor(t.TextMuted)
			if ui.PrimaryButton(c, "添加电脑").Disabled(a.store == nil).Clicked() {
				a.pairing = true
			}
		})
		return
	}
	ui.Column(c).Fill().Children(func() {
		ui.Column(c).Padding(18, 24).Gap(8).BorderWidth(0, 0, 1, 0).BorderColor(t.Border).Children(func() {
			ui.Row(c).AlignItems(ui.Center).Gap(12).Children(func() {
				ui.Text(c, chat.agent.Title()).FontSize(20).Bold().Grow(1)
				ui.Segmented(c, &a.mode, "对话", "终端").Label("主视图")
				if ui.Button(c, "文件").Selected(a.files).Clicked() {
					a.files = !a.files
					a.details = false
					if a.files {
						a.ensureFiles(m, chat)
					} else if chat.files != nil {
						chat.files.closePDF()
					}
				}
				if ui.Button(c, "详情").Clicked() {
					a.details = !a.details
					a.files = false
					if chat.files != nil {
						chat.files.closePDF()
					}
				}
			})
			ui.Text(c, m.config.Name+" / "+chat.agent.Cwd+" / "+chat.agent.Agent).TextColor(t.TextMuted).FontSize(12).Selectable()
			ui.Row(c).Gap(12).Children(func() {
				a.runStatusView(c, m, chat)
				ui.Text(c, m.status).FontSize(12).TextColor(t.TextMuted)
				if a.demo {
					ui.Text(c, "示例数据").FontSize(12).TextColor(t.Warning)
				}
			})
		})
		ui.Row(c).Grow(1).Children(func() {
			if !a.files || !filesFor(chat).expanded {
				ui.Column(c).Grow(1).FillHeight().Children(func() {
					if a.mode == 1 {
						a.terminalView(c, m, chat)
					} else {
						a.chatView(c, m, chat)
					}
				})
			}
			if a.details {
				a.detailView(c, m, chat)
			}
			if a.files {
				a.fileView(c, m, chat)
			}
		})
	})
}
func (a *application) chatView(c *ui.Context, m *machine, chat *conversation) {
	t := c.Theme()
	if chat.message != "" {
		ui.Text(c, chat.message).FontSize(12).TextColor(t.Warning).Padding(10, 24).Selectable()
	}
	ui.Row(c).Padding(7, 24).Gap(8).Children(func() {
		if chat.next != "" && ui.Button(c, "加载更早消息").Disabled(chat.loading).Clicked() {
			a.loadHistory(true)
		}
		if ui.Button(c, "刷新").Disabled(chat.loading || m.session == nil).Clicked() {
			a.loadHistory(false)
		}
		if chat.loading || chat.preparing {
			ui.Spinner(c).Size(15, 15)
			label := "正在同步…"
			if chat.historyOlder {
				label = "正在加载更早消息…"
			}
			ui.Text(c, label).FontSize(12).TextColor(t.TextMuted)
			if chat.historyProgress > 0 {
				ui.Progress(c, chat.historyProgress).Width(120)
			}
			if ui.Button(c, "取消加载").Clicked() {
				if chat.historyCancel != nil {
					chat.historyCancel()
				}
				chat.historyEpoch++
				chat.loading = false
				chat.preparing = false
				chat.message = "已取消加载"
			}
		}
	})
	a.timelineView(c, m, chat)
	ui.Column(c).Padding(14, 24, 20, 24).Gap(9).Children(func() {
		if chat.uncertain {
			ui.Row(c).Gap(8).Children(func() {
				ui.Text(c, "上次投递尚未确认。").FontSize(12).TextColor(t.Warning).Grow(1)
				if ui.Button(c, "已查看终端，解除锁定").Disabled(chat.pending).Clicked() {
					if a.store != nil {
						if err := a.store.saveDraft(m.config.ID+":"+chat.agent.Identity(), chat.draft, false); err != nil {
							a.status = err.Error()
						} else {
							chat.uncertain = false
						}
					}
				}
			})
		}
		ui.TextArea(c, &chat.draft).Lines(2, 7).Placeholder("给 Agent 一个任务…").Label("对话输入").Disabled(chat.pending).OnTextKey(func(k ui.TextKey) bool {
			if k.Key == ui.KeyEnter && k.Mods == 0 {
				chat.draft = k.Text()
				a.send()
				return true
			}
			return false
		})
		ui.Row(c).AlignItems(ui.Center).Children(func() {
			hint := "Enter 发送 · Shift+Enter 换行"
			if !chat.agent.CanChat() {
				hint = "主会话未确认，可使用终端"
			}
			if a.demo {
				hint = "演示模式"
			}
			ui.Text(c, hint).TextColor(t.TextMuted).FontSize(12).Grow(1)
			canSend := m.session != nil && m.config.Auth.Role == "controller" && chat.agent.CanChat() && !chat.pending && !chat.uncertain && strings.TrimSpace(chat.draft) != ""
			if chat.pending {
				ui.Spinner(c).Size(16, 16)
			}
			if ui.PrimaryButton(c, "发送").Disabled(!canSend).Clicked() {
				a.send()
			}
		})
	})
}
func (a *application) terminalView(c *ui.Context, m *machine, chat *conversation) {
	t := c.Theme()
	ui.Row(c).Padding(9, 20).Gap(10).AlignItems(ui.Center).Children(func() {
		label := m.termState
		if label == "" {
			label = "尚未连接"
		}
		ui.Text(c, label).FontSize(12).TextColor(t.TextMuted).Grow(1)
		if m.term != nil && ui.Button(c, "断开终端").Clicked() {
			m.term.Close()
			m.term = nil
			if m.remote != nil {
				m.remote.Close()
				m.remote = nil
			}
			m.termState = "已断开，远端 Agent 继续运行"
		}
	})
	if m.term != nil && m.termIdentity == chat.agent.Identity() {
		terminal.View(c.Key("terminal:"+m.termIdentity), m.term).Grow(1).FillWidth().AutoFocus()
		return
	}
	ui.Column(c).Grow(1).FillWidth().Center().Gap(14).Children(func() {
		ui.Text(c, "原生终端").FontSize(22).Bold()
		ui.Text(c, "连接后即可操作当前 Agent。").TextColor(t.TextMuted).FontSize(13)
		label := "连接原生终端"
		if a.demo {
			label = "打开本机终端"
		}
		if ui.PrimaryButton(c, label).Disabled(m.opening).Clicked() {
			a.openTerminal()
		}
	})
}
func (a *application) detailView(c *ui.Context, m *machine, chat *conversation) {
	t := c.Theme()
	ui.Scroll(c).Width(290).FillHeight().Padding(20).Gap(14).BorderWidth(0, 0, 0, 1).BorderColor(t.Border).Children(func() {
		ui.Text(c, "会话详情").Bold().FontSize(15)
		for _, row := range [][2]string{{"电脑", m.config.Name}, {"项目", chat.agent.Project}, {"工作目录", chat.agent.Cwd}, {"Agent", chat.agent.Agent}, {"连接", m.status}} {
			rc := c.Key(row[0])
			ui.Text(rc, row[0]).FontSize(11).TextColor(t.TextMuted)
			ui.Text(rc, row[1]).FontSize(12).Selectable()
		}
	})
}
