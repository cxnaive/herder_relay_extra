package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/coordinator"
	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

// Explicit local bindings are an operator-selected fallback, never a cwd/title
// search. They are fenced to one terminal, foreground process and OS boot.
type codexSessionBinding struct {
	PaneID       string `json:"pane_id"`
	TerminalID   string `json:"terminal_id"`
	CWD          string `json:"cwd"`
	Title        string `json:"terminal_title"`
	SessionID    string `json:"session_id"`
	PID          int    `json:"pid"`
	ProcessStart string `json:"process_start"`
}

var codexBindingID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

const codexBindingSource = "codex-local-binding:"

type codexBindingReader interface {
	PaneGet(context.Context, string) (*herdr.Pane, error)
	PaneProcessInfoFresh(context.Context, string) (*herdr.PaneProcessInfo, error)
}

func loadCodexBindings(dir string) []codexSessionBinding {
	if dir == "" {
		return nil
	}
	file, err := os.Open(filepath.Join(dir, "codex-session-bindings.json"))
	if err != nil {
		return nil
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() > 64*1024 {
		return nil
	}
	var data struct {
		Schema   int                   `json:"schema"`
		Bindings []codexSessionBinding `json:"bindings"`
	}
	decoder := json.NewDecoder(io.LimitReader(file, 64*1024+1))
	if decoder.Decode(&data) != nil || data.Schema != 1 || len(data.Bindings) > 32 {
		return nil
	}
	seen := make(map[string]bool)
	for _, b := range data.Bindings {
		if b.PaneID == "" || b.TerminalID == "" || !filepath.IsAbs(b.CWD) || b.Title == "" || !codexBindingID.MatchString(b.SessionID) || b.PID <= 0 || b.ProcessStart == "" || seen[b.PaneID] {
			return nil
		}
		seen[b.PaneID] = true
	}
	return data.Bindings
}

func codexBindingMatches(b codexSessionBinding, pane *herdr.Pane, info *herdr.PaneProcessInfo, birth string) bool {
	if pane == nil || pane.ID != b.PaneID || pane.TerminalID != b.TerminalID || pane.Cwd != b.CWD || pane.Agent != "codex" || birth == "" || birth != b.ProcessStart {
		return false
	}
	if id := strings.TrimSpace(pane.SessionRaw.Value); id != "" && id != b.SessionID {
		return false
	}
	title := codexTerminalTitle(pane)
	if title != b.Title {
		return false
	}
	pid, err := foregroundRoot(info, b.PaneID)
	if err != nil || pid != b.PID {
		return false
	}
	process := info.ForegroundProcesses[0]
	return process.Cwd == b.CWD && len(process.Argv) > 0 && filepath.Base(process.Argv[0]) == "codex"
}

func verifyCodexBinding(ctx context.Context, reader codexBindingReader, b codexSessionBinding, birth func(int) (string, error)) bool {
	if ctx.Err() != nil {
		return false
	}
	start, err := birth(b.PID)
	if err != nil || start != b.ProcessStart {
		return false
	}
	pane, err := reader.PaneGet(ctx, b.PaneID)
	if err != nil {
		return false
	}
	info, err := reader.PaneProcessInfoFresh(ctx, b.PaneID)
	if err != nil || !codexBindingMatches(b, pane, info, start) {
		return false
	}
	after, err := birth(b.PID)
	return err == nil && after == start && ctx.Err() == nil
}

func (s *Server) enrichCodexBindings(ctx context.Context, agents []*coordinator.AgentState) {
	bindings := loadCodexBindings(s.cfg.RuntimeDir)
	if len(bindings) == 0 {
		return
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	for _, agent := range agents {
		if agent.Agent != "codex" || strings.TrimSpace(agent.Session) != "" || (agent.ServerSessionID != "" && agent.ServerSessionID != "primary") {
			continue
		}
		for _, b := range bindings {
			if b.PaneID != agent.PaneID || b.TerminalID != agent.TerminalID || b.CWD != agent.Cwd {
				continue
			}
			if !verifyCodexBinding(check, s.herdrC, b, codexProcessStart) || !s.conversationM.CodexSessionProjectMatches(b.CWD, b.SessionID) || !s.conversationM.CodexPinnedTitleCurrent(b.CWD, b.SessionID, b.Title) {
				continue
			}
			agent.Session = b.SessionID
			agent.SessionKind = "id"
			agent.ConversationProcess = b.ProcessStart
			agent.ConversationSource = codexBindingSource + b.SessionID
		}
	}
}

func (s *Server) codexBindingCurrent(ctx context.Context, agent *coordinator.AgentState) bool {
	if agent == nil {
		return false
	}
	if !strings.HasPrefix(agent.ConversationSource, codexBindingSource) {
		return true
	}
	check, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	for _, b := range loadCodexBindings(s.cfg.RuntimeDir) {
		if b.PaneID == agent.PaneID && b.TerminalID == agent.TerminalID && b.CWD == agent.Cwd && b.SessionID == agent.SessionID && b.ProcessStart == agent.ConversationProcess {
			return verifyCodexBinding(check, s.herdrC, b, codexProcessStart) && s.conversationM.CodexPinnedTitleCurrent(b.CWD, b.SessionID, b.Title)
		}
	}
	return false
}

func (s *Server) conversationSelectionCurrent(ctx context.Context, agent *coordinator.AgentState) bool {
	return s.codexRuntimeCurrent(ctx, agent) && s.codexBindingCurrent(ctx, agent) && s.kimiSelectionCurrent(ctx, agent)
}

var errCodexProcessStart = errors.New("Codex process birth identity is unavailable")
