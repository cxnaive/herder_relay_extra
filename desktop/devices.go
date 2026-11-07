package main

import (
	"context"
	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
	"time"
)

type deviceDialog struct {
	machine                               *machine
	epoch                                 uint64
	busy                                  bool
	message, action, id, name, role, link string
	devices                               []relay.Message
}

func (a *application) openDevices(m *machine) {
	d := &deviceDialog{machine: m, epoch: m.epoch, role: "reader"}
	a.deviceDialog = d
	a.loadDevices(d)
}
func (a *application) loadDevices(d *deviceDialog) {
	m := d.machine
	if m.session == nil || m.epoch != d.epoch {
		d.message = "连接已变化，请重新打开"
		return
	}
	d.busy = true
	s := m.session
	go func() {
		ctx, cancel := context.WithTimeout(a.ctx, 15*time.Second)
		defer cancel()
		data, err := s.Request(ctx, relay.Message{"type": "device_list"})
		a.update(func() {
			d.busy = false
			if m.epoch != d.epoch {
				d.message = "连接已变化，请重新打开"
				return
			}
			if err != nil {
				d.message = err.Error()
			} else {
				d.devices = relay.Decode[[]relay.Message](data["devices"])
			}
		})
	}()
}
func (a *application) submitDevice(d *deviceDialog) {
	m := d.machine
	if d.busy || m.epoch != d.epoch || !managementReady(m) {
		return
	}
	req := relay.Message{"type": d.action, "device_id": d.id}
	if d.action != "revoke_device" {
		value, err := relay.RenamePayload(d.name)
		if err != nil {
			d.message = err.Error()
			return
		}
		req["name"] = value["name"]
	}
	if d.action == "create_device_invitation" {
		if d.role != "reader" && d.role != "controller" {
			return
		}
		req["role"] = d.role
	}
	action := d.action
	d.busy = true
	d.message = ""
	a.writeOperation(m, req, groupKey("devices", m.config.ID), 30*time.Second, func(data relay.Message, err error) {
		d.busy = false
		if err != nil {
			d.message = err.Error()
			return
		}
		d.action = ""
		if action == "create_device_invitation" {
			link, err := relay.InvitationLink(m.config, data, time.Now())
			if err != nil {
				d.message = err.Error()
			} else {
				d.link = link
			}
			return
		}
		d.message = "设备设置已更新"
		a.loadDevices(d)
	})
}
func (a *application) devicesView(c *ui.Context) {
	d := a.deviceDialog
	if d == nil {
		return
	}
	open := true
	m := d.machine
	ui.Modal(c, &open, func() {
		ui.Row(c).Children(func() {
			ui.Text(c, "配对设备 · "+m.config.Name).Bold().FontSize(19).Grow(1)
			if ui.Button(c, "关闭设备管理").Clicked() {
				open = false
			}
		})
		a.operationNotice(c, groupKey("devices", m.config.ID))
		ui.Scroll(c).Height(270).Gap(10).Children(func() {
			for _, device := range d.devices {
				id := relay.Text(device, "device_id")
				name := relay.Text(device, "name")
				role := relay.Text(device, "role")
				if role == "controller" {
					role = "控制者"
				} else {
					role = "只读"
				}
				ui.Row(c.Key(id)).Gap(8).AlignItems(ui.Center).Children(func() {
					label := name + " · " + role
					if id == m.config.Auth.DeviceID {
						label += " · 本设备"
					}
					ui.Text(c, label).Grow(1)
					if ui.Button(c, "改名").Disabled(d.busy || !managementReady(m)).Clicked() {
						d.action = "rename_device"
						d.id = id
						d.name = name
						d.link = ""
					}
					if ui.Button(c, "撤销授权").Disabled(d.busy || !managementReady(m)).Clicked() {
						d.action = "revoke_device"
						d.id = id
						d.name = name
						d.link = ""
					}
				})
			}
		})
		ui.Row(c).Gap(8).Children(func() {
			if ui.Button(c, "刷新设备").Disabled(d.busy).Clicked() {
				a.loadDevices(d)
			}
			if ui.Button(c, "邀请新设备").Disabled(d.busy || !managementReady(m)).Clicked() {
				d.action = "create_device_invitation"
				d.id = ""
				d.name = ""
				d.role = "reader"
				d.link = ""
			}
		})
		if d.action != "" {
			if d.action == "revoke_device" {
				ui.Text(c, "撤销「"+d.name+"」的连接权限？").TextColor(c.Theme().Warning)
			} else {
				labeledInput(c, "设备名称", &d.name, "")
			}
			if d.action == "create_device_invitation" {
				choice := "只读"
				if d.role == "controller" {
					choice = "控制者"
				}
				ui.Select(c, &choice, []string{"只读", "控制者"}).Label("邀请权限")
				d.role = "reader"
				if choice == "控制者" {
					d.role = "controller"
				}
				ui.Text(c, "控制者可以发送任务并管理 Agent。").FontSize(12)
			}
			ui.Row(c).Gap(8).Children(func() {
				if ui.Button(c, "取消操作").Disabled(d.busy).Clicked() {
					d.action = ""
				}
				if ui.PrimaryButton(c, "确认设备操作").Disabled(d.busy || a.operations[groupKey("devices", m.config.ID)] != "").Clicked() {
					a.submitDevice(d)
				}
			})
		}
		if d.link != "" {
			ui.Text(c, "邀请已生成，10 分钟内可使用一次。").FontSize(12)
			if ui.Button(c, "复制邀请链接").Clicked() {
				mygo.Clipboard.WriteText(d.link)
			}
		}
		if d.busy {
			ui.Spinner(c).Size(18, 18)
		}
		if d.message != "" {
			ui.Text(c, d.message).FontSize(12).Selectable()
		}
	}).Width(680)
	if !open {
		a.deviceDialog = nil
	}
}
