package main

import (
	"context"
	"path"
	"strings"
	"time"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo/ui"
)

type fileAccessDialog struct {
	machine              *machine
	epoch                uint64
	busy, known, enabled bool
	message              string
}

func (a *application) fileReader(m *machine) relay.FileReader {
	if m.fileContext == nil {
		m.fileContext, m.fileCancel = context.WithCancel(a.ctx)
	}
	var requester relay.Requester
	if m.session != nil {
		requester = m.session
	}
	return relay.FileReader{Requester: requester, Context: m.fileContext, Filesystem: m.filesystemRead}
}
func (a *application) applyFileAccess(m *machine, enabled, force bool) {
	enabled = enabled && m.config.Auth.Role == "controller" && m.capabilities["filesystem_read_opt_in_v1"]
	if !force && m.filesystemRead == enabled {
		return
	}
	if m.fileCancel != nil {
		m.fileCancel()
	}
	m.fileContext, m.fileCancel = context.WithCancel(a.ctx)
	m.filesystemRead = enabled
	m.fileEpoch++
	for key, chat := range a.chats {
		if !strings.HasPrefix(key, m.config.ID+":") {
			continue
		}
		if f := chat.files; f != nil {
			f.closePDF()
			if f.cancel != nil {
				f.cancel()
			}
			if f.cancelDownload != nil {
				f.cancelDownload()
			}
		}
		chat.files = nil
		chat.images = nil
		chat.md = nil
	}
	if a.selectedMachine == m.config.ID {
		a.imagePreview = nil
	}
}
func fileHome(m *machine, agent relay.Agent) string {
	if m.filesystemRead {
		return strings.TrimPrefix(path.Clean(agent.Cwd), "/")
	}
	return ""
}
func (a *application) openFileAccess(m *machine) {
	d := &fileAccessDialog{machine: m, epoch: m.epoch}
	a.fileAccessDialog = d
	a.readFileAccess(d)
}
func (a *application) readFileAccess(d *fileAccessDialog) {
	m := d.machine
	if d.busy || m.session == nil || m.epoch != d.epoch {
		return
	}
	d.busy = true
	d.known = false
	s := m.session
	go func() {
		ctx, cancel := context.WithTimeout(a.ctx, 15*time.Second)
		defer cancel()
		data, err := s.Request(ctx, relay.Message{"type": "file_access_get"})
		a.update(func() {
			d.busy = false
			if m.epoch != d.epoch {
				d.message = "连接已变化，请重新打开"
				return
			}
			if err != nil {
				d.message = err.Error()
				return
			}
			enabled, err := relay.FileAccessValue(data)
			if err != nil {
				d.message = err.Error()
				return
			}
			a.applyFileAccess(m, enabled, false)
			d.enabled = m.filesystemRead
			d.known = true
			d.message = ""
			key := groupKey("file-access", m.config.ID)
			if a.operations[key] != "" && !a.busyOperations[key] {
				if err := a.setOperation(key, ""); err != nil {
					d.message = err.Error()
					d.known = false
				}
			}
		})
	}()
}
func (a *application) setFileAccess(d *fileAccessDialog) {
	m := d.machine
	if d.busy || !d.known || m.epoch != d.epoch || !managementReady(m) || !m.capabilities["filesystem_read_opt_in_v1"] {
		return
	}
	enabled := !d.enabled
	d.busy = true
	d.known = false
	d.message = ""
	// Revoke local readers immediately while the server processes the change.
	if !enabled {
		a.applyFileAccess(m, false, true)
	}
	a.writeOperation(m, relay.Message{"type": "file_access_set", "filesystem_read": enabled}, groupKey("file-access", m.config.ID), 20*time.Second, func(data relay.Message, err error) {
		d.busy = false
		if m.epoch != d.epoch {
			d.message = "连接已变化，请重新读取权限"
			return
		}
		if err != nil {
			d.message = err.Error() + "；请重新读取权限确认。"
			return
		}
		actual, err := relay.FileAccessValue(data)
		if err != nil || actual != enabled {
			d.message = "权限结果未确认，请重新读取"
			return
		}
		a.applyFileAccess(m, actual, false)
		d.enabled = m.filesystemRead
		d.known = true
		d.message = "访问范围已更新"
	})
}
func (a *application) fileAccessView(c *ui.Context) {
	d := a.fileAccessDialog
	if d == nil {
		return
	}
	open := true
	m := d.machine
	ui.Modal(c, &open, func() {
		ui.Row(c).Children(func() {
			ui.Text(c, "文件访问范围").FontSize(19).Bold().Grow(1)
			if ui.Button(c, "关闭权限窗口").Clicked() {
				open = false
			}
		})
		ui.Text(c, m.config.Name).TextColor(c.Theme().TextMuted)
		if d.busy {
			ui.Spinner(c).Size(18, 18)
		}
		if d.known {
			if d.enabled {
				ui.Text(c, "当前：电脑文件 · 只读").Bold()
				ui.Text(c, "恢复工作区限制会关闭当前预览，并取消文件读取和下载。")
			} else {
				ui.Text(c, "当前：Agent 工作区").Bold()
				ui.Text(c, "允许后，此设备可浏览、预览和下载 Relay 用户有权限读取的电脑文件，包括工作区外的私密文件。仅限只读。")
				ui.Text(c, "授权由这台电脑上的 Relay 保存，可随时在此关闭。").FontSize(12).TextColor(c.Theme().TextMuted)
			}
		}
		if d.message != "" {
			ui.Text(c, d.message).FontSize(12).Selectable()
		}
		ui.Row(c).Gap(8).Children(func() {
			if ui.Button(c, "重新读取权限").Disabled(d.busy).Clicked() {
				a.readFileAccess(d)
			}
			label := "允许只读访问"
			if d.enabled {
				label = "恢复工作区限制"
			}
			if ui.PrimaryButton(c, label).Disabled(d.busy || !d.known || !managementReady(m) || a.operations[groupKey("file-access", m.config.ID)] != "").Clicked() {
				a.setFileAccess(d)
			}
		})
	}).Width(570)
	if !open {
		a.fileAccessDialog = nil
	}
}
