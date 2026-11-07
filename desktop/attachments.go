package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/transfer"
	"github.com/egoist/mygo/ui"
)

func uploadEnabled(m *machine, chat *conversation) bool {
	return managementReady(m) && chat != nil && chat.agent.CanChat() && chat.agent.Agent != "pi" && chat.agent.Agent != "omp" && !chat.pending && !chat.uncertain && !chat.uploading && chat.agent.Status != "blocked"
}
func readUploadFiles(paths []string) ([]relay.UploadFile, error) {
	if len(paths) > 8 {
		return nil, errors.New("每次最多 8 个附件")
	}
	files := []relay.UploadFile{}
	total := 0
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			return nil, err
		}
		info, err := f.Stat()
		if err != nil {
			f.Close()
			return nil, err
		}
		if !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 20*1024*1024 {
			f.Close()
			return nil, errors.New("请选择不超过 20 MiB 的普通文件")
		}
		data, err := io.ReadAll(io.LimitReader(f, 20*1024*1024+1))
		f.Close()
		if err != nil {
			return nil, err
		}
		total += len(data)
		if len(data) > 20*1024*1024 || total > 50*1024*1024 {
			return nil, errors.New("单个附件最多 20 MiB，附件总量最多 50 MiB")
		}
		media := mime.TypeByExtension(strings.ToLower(filepath.Ext(path)))
		if media == "" {
			media = http.DetectContentType(data)
		}
		media, _, _ = mime.ParseMediaType(media)
		files = append(files, relay.UploadFile{Name: filepath.Base(path), MediaType: media, Data: data})
	}
	return files, nil
}
func (a *application) pickAttachments(m *machine, chat *conversation) {
	if !uploadEnabled(m, chat) {
		return
	}
	epoch, target := m.epoch, chat.agent.Target
	go func() {
		paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{Parent: a.win, Title: "添加附件", Multiple: true})
		a.update(func() {
			if err != nil {
				chat.message = err.Error()
				return
			}
			current, selected := a.selected()
			if current != m || selected != chat || m.epoch != epoch || chat.agent.Target != target {
				return
			}
			a.uploadPaths(paths)
		})
	}()
}
func (a *application) uploadPaths(paths []string) {
	m, chat := a.selected()
	if len(paths) == 0 {
		return
	}
	if !uploadEnabled(m, chat) {
		a.status = "请先选择可发送附件的 Agent"
		return
	}
	a.prepareUpload(m, chat, func() ([]relay.UploadFile, error) { return readUploadFiles(paths) })
}
func (a *application) pasteAttachments(m *machine, chat *conversation) {
	if !uploadEnabled(m, chat) {
		return
	}
	if paths, err := mygo.Clipboard.ReadFiles(); err == nil && len(paths) > 0 {
		a.uploadPaths(paths)
		return
	}
	// Read only image representations explicitly requested by the user.
	for _, item := range []struct {
		format      transfer.Format
		name, media string
	}{{transfer.Format("image/png"), "clipboard.png", "image/png"}, {transfer.Format("image/jpeg"), "clipboard.jpg", "image/jpeg"}} {
		if data, err := mygo.Clipboard.ReadFormat(item.format); err == nil && len(data) > 0 {
			a.prepareUpload(m, chat, func() ([]relay.UploadFile, error) {
				return []relay.UploadFile{{Name: item.name, MediaType: item.media, Data: data}}, nil
			})
			return
		}
	}
	chat.message = "剪贴板中没有图片或文件"
}
func (a *application) prepareUpload(m *machine, chat *conversation, read func() ([]relay.UploadFile, error)) {
	if !uploadEnabled(m, chat) || a.store == nil || !a.agentWriteAllowed(m, chat.agent, "upload_begin") {
		return
	}
	chat.uploading = true
	chat.uploadProgress = 0
	chat.uploadLabel = "正在读取附件…"
	agent, epoch, s := chat.agent, m.epoch, m.session
	ctx, cancel := context.WithTimeout(a.ctx, 10*time.Minute)
	chat.uploadCancel = cancel
	existingCount := len(chat.attachments)
	var existingBytes int64
	for _, att := range chat.attachments {
		existingBytes += att.Bytes
	}
	go func() {
		defer cancel()
		files, err := read()
		var atts []relay.Attachment
		if err == nil {
			err = relay.ValidateUpload(files)
		}
		if err == nil {
			total := existingBytes
			for _, f := range files {
				total += int64(len(f.Data))
			}
			if existingCount+len(files) > 8 || total > 50*1024*1024 {
				err = errors.New("每条消息最多 8 个附件，总量最多 50 MiB")
			}
		}
		if err == nil {
			atts, err = relay.Upload(ctx, s, agent, files, func(name string, sent, total int64) {
				a.update(func() {
					if chat.uploading && m.epoch == epoch {
						chat.uploadLabel = "正在上传 " + name
						chat.uploadProgress = float64(sent) / float64(total)
					}
				})
			})
		}
		a.update(func() {
			chat.uploading = false
			chat.uploadCancel = nil
			if m.epoch != epoch {
				return
			}
			if _, ok := exactAgent(m, agent); !ok {
				return
			}
			if err != nil {
				chat.message = "附件上传未完成：" + err.Error()
				return
			}
			all := append(append([]relay.Attachment{}, chat.attachments...), atts...)
			if err = a.store.saveAttachments(m.config.ID+":"+agent.Identity(), all); err != nil {
				chat.message = err.Error()
				return
			}
			chat.attachments = all
			chat.message = ""
		})
	}()
}
func (a *application) attachmentsView(c *ui.Context, m *machine, chat *conversation) {
	if chat.uploading {
		ui.Row(c).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Text(c, chat.uploadLabel).FontSize(12).Grow(1)
			ui.Progress(c, chat.uploadProgress).Width(110)
			if ui.Button(c, "取消上传").Clicked() && chat.uploadCancel != nil {
				chat.uploadCancel()
			}
		})
	}
	for i, att := range chat.attachments {
		ui.Row(c.Key(att.Ref)).Gap(8).AlignItems(ui.Center).Children(func() {
			ui.Text(c, fmt.Sprintf("%s · %.1f KiB", att.Name, float64(att.Bytes)/1024)).FontSize(12).Grow(1)
			if !att.ExpiresAt.After(time.Now()) {
				ui.Text(c, "已过期").TextColor(c.Theme().Warning).FontSize(12)
			}
			if ui.Button(c, "移除").Disabled(chat.pending || chat.uploading || chat.uncertain).Clicked() {
				next := append(append([]relay.Attachment{}, chat.attachments[:i]...), chat.attachments[i+1:]...)
				if err := a.store.saveAttachments(m.config.ID+":"+chat.agent.Identity(), next); err != nil {
					chat.message = err.Error()
				} else {
					chat.attachments = next
				}
			}
		})
	}
}
