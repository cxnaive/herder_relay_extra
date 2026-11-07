package relay

import (
	"context"
	"errors"
)

// FileReader captures one device-authorized scope for an entire transfer.
// Cancellation revokes queued reads and prevents late replies being consumed.
type FileReader struct {
	Requester  Requester
	Context    context.Context
	Filesystem bool
}

func (s FileReader) Request(ctx context.Context, m Message) (Message, error) {
	switch Text(m, "type") {
	case "workspace_list", "workspace_file", "workspace_file_read", "workspace_file_download":
	default:
		return nil, errors.New("此连接仅用于读取文件")
	}
	if s.Requester == nil {
		return nil, errors.New("电脑未连接")
	}
	if err := s.Context.Err(); err != nil {
		return nil, err
	}
	readCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(s.Context, cancel)
	defer stop()
	request := Message{}
	for k, v := range m {
		request[k] = v
	}
	request["file_scope"] = "workspace"
	if s.Filesystem {
		request["file_scope"] = "filesystem"
	}
	reply, err := s.Requester.Request(readCtx, request)
	if cancelled := s.Context.Err(); cancelled != nil {
		return nil, cancelled
	}
	if readCtx.Err() != nil {
		return nil, readCtx.Err()
	}
	return reply, err
}
func FileAccessValue(data Message) (bool, error) {
	enabled, ok := data["filesystem_read"].(bool)
	if !ok {
		return false, errors.New("未收到有效的文件访问权限")
	}
	return enabled, nil
}
