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

var documentReadSlots = make(chan struct{}, 2)

func (s *Server) handleWorkspaceDocument(ctx context.Context, client *transport.ClientConn, inbound protocol.Inbound) {
	fail := func(message string) {
		s.sendCommandResult(client, inbound.RequestID, inbound.Type, false, "failed", message, inbound.PaneID, nil)
	}
	if !validDocumentEnvelope(inbound) || validateExactPaneTarget(s.state, inbound, true) != nil {
		fail("The exact Agent workspace is no longer available")
		return
	}
	select {
	case documentReadSlots <- struct{}{}:
		defer func() { <-documentReadSlots }()
	default:
		fail("Another document is loading; please retry")
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	agent, exists := s.state.Agent(inbound.PaneID)
	if !exists || !s.conversationSelectionCurrent(ctx, agent) {
		fail("Agent session changed; reopen the document from its conversation")
		return
	}
	cwd, scopeErr := s.fileReadRoot(client, inbound, strings.TrimSpace(agent.Cwd))
	if scopeErr != nil {
		fail(scopeErr.Error())
		return
	}
	var data any
	var err error
	if inbound.Type == "workspace_file_download" {
		data, err = workspace.ReadDownloadChunk(cwd, inbound.Path, inbound.Cursor, inbound.ExpectedRevision, inbound.MetadataOnly)
	} else if inbound.Type == "workspace_file" {
		data, err = workspace.ReadFile(cwd, inbound.Path)
	} else {
		data, err = workspace.ReadDocumentChunk(cwd, inbound.Path, inbound.Cursor, inbound.ExpectedRevision)
	}
	if err != nil {
		message := "Document could not be read"
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
	if currentScopeErr != nil || ctx.Err() != nil || !exists || !sameConversationTuple(agent, current) || current.Cwd != agent.Cwd || validateExactPaneTarget(s.state, inbound, true) != nil || !s.conversationSelectionCurrent(ctx, current) || s.authorizeDeviceAction(client, action, inbound.DeviceID) != nil {
		fail("Agent workspace or authorization changed during document read")
		return
	}
	s.sendCommandResult(client, inbound.RequestID, inbound.Type, true, "completed", "", inbound.PaneID, data)
}

func validDocumentEnvelope(inbound protocol.Inbound) bool {
	return inbound.Protocol == protocol.Version && inbound.Target != nil && inbound.PaneID != ""
}
