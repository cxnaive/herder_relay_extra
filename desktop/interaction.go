package main

import (
	"fmt"

	"github.com/cxnaive/herdr-desktop/internal/relay"
	"github.com/egoist/mygo/ui"
)

type questionForm struct {
	id            string
	selected      map[int]bool
	other         bool
	text, message string
	awaiting      string
}

func (a *application) interactionView(c *ui.Context, m *machine, chat *conversation) {
	captured := chat.agent
	q := captured.Interaction
	if q == nil && captured.Status != "blocked" {
		return
	}
	enabled := a.agentWriteAllowed(m, captured, "respond")
	ui.Column(c.Key("attention")).FillWidth().MaxHeight(300).Padding(12, 24).Gap(9).Background(c.Theme().Surface).Children(func() {
		if q == nil || q.ID == "" {
			ui.Text(c, "等待审批").Bold().FontSize(13)
			ui.Text(c, captured.Prompt).Selectable()
			if captured.Command != "" && captured.Command != captured.Prompt {
				ui.Text(c, captured.Command).Selectable().FontSize(12)
			}
			if captured.ApprovalFingerprint == "" || len(captured.Options) == 0 {
				ui.Text(c, "请切换终端处理当前请求。").FontSize(12)
				return
			}
			ui.Row(c).Gap(8).Children(func() {
				for i, label := range captured.Options {
					if ui.Button(c.Key(fmt.Sprint(i)), label).Disabled(!enabled || chat.question.awaiting == captured.ApprovalFingerprint).Clicked() {
						live, ok := exactAgent(m, captured)
						if !ok {
							chat.message = "Agent 已变化，请刷新"
							return
						}
						payload, err := relay.ApprovalPayload(live, captured, i)
						if err != nil {
							chat.message = err.Error()
							return
						}
						chat.question.awaiting = captured.ApprovalFingerprint
						a.writeAgent(m, captured, "respond", payload, func(_ relay.Message, err error) {
							if err != nil {
								chat.message = err.Error()
								if !relay.UnknownOutcome(err) {
									chat.question.awaiting = ""
								}
							}
						})
					}
				}
			})
			return
		}
		f := &chat.question
		if f.id != q.ID {
			*f = questionForm{id: q.ID, selected: map[int]bool{}}
			for _, o := range q.Options {
				f.selected[o.Index] = o.Selected
			}
			if q.Other != nil {
				f.other = q.Other.Selected
				f.text = q.Other.Text
			}
		}
		enabled = enabled && f.awaiting != q.ID
		ui.Row(c).Children(func() {
			ui.Text(c, "需要你的回答").Bold().Grow(1)
			if q.QuestionTotal > 0 {
				ui.Text(c, fmt.Sprintf("%d / %d", q.QuestionIndex, q.QuestionTotal)).FontSize(12)
			}
		})
		ui.Scroll(c).MaxHeight(175).Gap(7).Children(func() {
			ui.Text(c, q.Question).Selectable()
			for _, o := range q.Options {
				oc := c.Key(fmt.Sprint(o.Index))
				was := f.selected[o.Index]
				chosen := was
				ui.Checkbox(oc, &chosen, o.Label).Disabled(!enabled)
				if chosen != was {
					if chosen && q.Kind == "single_select" {
						clear(f.selected)
						f.other = false
					}
					f.selected[o.Index] = chosen
				}
				if o.Description != "" {
					ui.Text(oc, o.Description).FontSize(12).TextColor(c.Theme().TextMuted)
				}
				for _, s := range o.Summary {
					ui.Text(oc, s.Question+"："+s.Answer).FontSize(12).Selectable()
				}
			}
			if o := q.Other; o != nil && !o.Hidden {
				label := o.Label
				if label == "" {
					label = "自定义答案"
				}
				was := f.other
				ui.Checkbox(c, &f.other, label).Disabled(!enabled)
				if !was && f.other && q.Kind == "single_select" {
					clear(f.selected)
				}
				if f.other {
					ui.TextInput(c, &f.text).Label("自定义答案").Placeholder(o.Placeholder).Disabled(!enabled)
				}
			}
		})
		if f.message != "" {
			ui.Text(c, f.message).FontSize(12).TextColor(c.Theme().Warning)
		}
		send := func(action string, payload relay.Message) {
			live, ok := exactAgent(m, captured)
			if !ok || live.Interaction == nil || live.Interaction.ID != q.ID {
				f.message = "问题已变化，请处理最新问题"
				return
			}
			payload["interaction_id"] = q.ID
			f.awaiting = q.ID
			a.writeAgent(m, captured, action, payload, func(_ relay.Message, err error) {
				if err != nil {
					f.message = err.Error()
					if !relay.UnknownOutcome(err) {
						f.awaiting = ""
					}
				}
			})
		}
		ui.Row(c).Gap(8).Children(func() {
			if q.CanGoBack && ui.Button(c, "上一题").Disabled(!enabled).Clicked() {
				send("navigate_question", relay.Message{"direction": "previous"})
			}
			if q.CanChat && ui.Button(c, "进一步讨论").Disabled(!enabled).Clicked() {
				send("clarify_question", relay.Message{})
			}
			label := q.SubmitLabel
			if label == "" {
				label = "提交答案"
			}
			if ui.PrimaryButton(c, label).Disabled(!enabled).Clicked() {
				indices := []int{}
				for index, v := range f.selected {
					if v {
						indices = append(indices, index)
					}
				}
				payload, err := q.Answer(indices, f.other, f.text)
				if err != nil {
					f.message = err.Error()
				} else {
					send("answer_question", payload)
				}
			}
		})
	})
}
