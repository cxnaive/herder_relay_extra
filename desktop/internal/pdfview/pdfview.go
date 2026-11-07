// Package pdfview displays verified PDF bytes in the system's embedded reader.
package pdfview

import (
	"bytes"
	"crypto/rand"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/egoist/mygo"
)

type item struct {
	data []byte
	page *mygo.Page
}
type store struct {
	sync.RWMutex
	documents map[string]item
}

var documents = store{documents: map[string]item{}}

func Register() error { return mygo.Protocol.Handle("herdr-preview", &documents) }
func (s *store) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.RLock()
	doc, ok := s.documents[r.URL.Path]
	s.RUnlock()
	if !ok || r.URL.Host != "localhost" || (doc.page != nil && mygo.CallerPage(r.Context()) != doc.page) {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/pdf")
	w.Header().Set("Content-Disposition", `inline; filename="document.pdf"`)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "document.pdf", time.Time{}, bytes.NewReader(doc.data))
}

type Viewer struct {
	Web *mygo.WebView
	key string
}

func New(win *mygo.Window, data []byte, ready func(error)) (*Viewer, error) {
	if len(data) > 12*1024*1024 || !bytes.HasPrefix(data, []byte("%PDF-")) {
		return nil, errors.New("PDF 文件无效或超过预览大小限制")
	}
	web, err := win.NewWebView(mygo.WebViewOptions{Page: mygo.PageOptions{Untrusted: true, Session: mygo.NewEphemeralSession(), DevTools: mygo.DevToolsDisabled}})
	if err != nil {
		return nil, err
	}
	key := "/" + rand.Text() + "/document.pdf"
	documents.Lock()
	documents.documents[key] = item{data: data, page: web.Page()}
	documents.Unlock()
	v := &Viewer{Web: web, key: key}
	web.Page().OnWillNavigate(func(e *mygo.NavigateEvent) { e.PreventDefault() })
	web.Page().OnDidFinishLoad(func() {
		if ready != nil {
			ready(nil)
		}
	})
	web.Page().OnDidFailLoad(func(e *mygo.LoadError) {
		if ready != nil {
			ready(errors.New("PDF 预览加载失败"))
		}
	})
	if err = web.Page().LoadURL("herdr-preview://localhost" + key); err != nil {
		v.Close()
		return nil, err
	}
	return v, nil
}
func (v *Viewer) Close() {
	if v == nil {
		return
	}
	documents.Lock()
	delete(documents.documents, v.key)
	documents.Unlock()
	if v.Web != nil {
		v.Web.Destroy()
	}
}
func (v *Viewer) URL() string { return "herdr-preview://localhost" + strings.TrimSpace(v.key) }
