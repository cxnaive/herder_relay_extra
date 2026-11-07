package pdfview

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestPreviewIsPrivateBoundedAndSupportsRanges(t *testing.T) {
	s := store{documents: map[string]item{"/token/document.pdf": {data: []byte("%PDF-1.4\nfixture")}}}
	r := httptest.NewRequest("GET", "herdr-preview://localhost/token/document.pdf", nil)
	r.Header.Set("Range", "bytes=0-7")
	w := httptest.NewRecorder()
	s.ServeHTTP(w, r)
	if w.Code != 206 || w.Body.String() != "%PDF-1.4" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal(w.Code, w.Body.String(), w.Header())
	}
	for _, u := range []string{"herdr-preview://localhost/missing/document.pdf", "herdr-preview://other/token/document.pdf"} {
		w = httptest.NewRecorder()
		s.ServeHTTP(w, httptest.NewRequest("GET", u, nil))
		if w.Code != 404 {
			t.Fatal("unregistered document served")
		}
	}
	if _, err := New(nil, []byte(strings.Repeat("x", 12*1024*1024+1)), nil); err == nil {
		t.Fatal("oversize accepted")
	}
}
