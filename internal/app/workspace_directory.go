package app

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"github.com/0cv/herdr-mobile-relay/internal/transport"
	"github.com/0cv/herdr-mobile-relay/internal/workspace"
)

var directoryReadSlots = make(chan struct{}, 2)

func (s *Server) handleWorkspaceDirectory(ctx context.Context, client *transport.ClientConn, inbound protocol.Inbound) {
	fail := func(message string) {
		s.sendCommandResult(client, inbound.RequestID, inbound.Type, false, "failed", message, inbound.PaneID, nil)
	}
	if !validDirectoryEnvelope(inbound) || validateExactPaneTarget(s.state, inbound, true) != nil {
		fail("The exact Agent workspace is no longer available")
		return
	}
	select {
	case directoryReadSlots <- struct{}{}:
		defer func() { <-directoryReadSlots }()
	default:
		fail("Another directory is loading; please retry")
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	agent, exists := s.state.Agent(inbound.PaneID)
	if !exists || !s.workspaceSelectionCurrent(ctx, agent) {
		fail("Agent session changed; reopen its workspace")
		return
	}
	cwd, scopeErr := s.fileReadRoot(client, inbound, strings.TrimSpace(agent.Cwd))
	if scopeErr != nil {
		fail(scopeErr.Error())
		return
	}
	data, err := workspace.ListDirectory(ctx, cwd, inbound.Path, inbound.Cursor, inbound.Limit)
	if err != nil {
		message := "Directory could not be read"
		var public *workspace.Error
		if errors.As(err, &public) {
			message = public.Message
		}
		fail(message)
		return
	}
	current, exists := s.state.Agent(inbound.PaneID)
	action, _ := protocol.ClassifyAction(inbound.Type)
	_, currentScopeErr := s.fileReadRoot(client, inbound, cwd)
	if currentScopeErr != nil || ctx.Err() != nil || !exists || !sameConversationTuple(agent, current) || current.Cwd != agent.Cwd || validateExactPaneTarget(s.state, inbound, true) != nil || !s.workspaceSelectionCurrent(ctx, current) || s.authorizeDeviceAction(client, action, inbound.DeviceID) != nil {
		fail("Agent workspace or authorization changed during directory read")
		return
	}
	s.sendCommandResult(client, inbound.RequestID, inbound.Type, true, "completed", "", inbound.PaneID, data)
}

func validDirectoryEnvelope(inbound protocol.Inbound) bool {
	return inbound.Protocol == protocol.Version && inbound.Target != nil && inbound.PaneID != ""
}
