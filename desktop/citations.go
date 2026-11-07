package main

import (
	"errors"
	"fmt"
	"net/url"
	"path"
	"strings"
	"unicode"

	"github.com/egoist/mygo/ui/markdown/parser"
)

type fileCitation struct{ raw, path, token string }

func citationAt(src string, start int) (int, string, bool) {
	i := start + len(":codex-file-citation{")
	limit := min(len(src), start+8192)
	attrs := map[string]string{}
	space := func() {
		for i < limit && (src[i] == ' ' || src[i] == '\t') {
			i++
		}
	}
	for i < limit {
		space()
		if i >= limit {
			return 0, "", false
		}
		if src[i] == '}' {
			return i + 1, attrs["path"], strings.TrimSpace(attrs["path"]) != ""
		}
		begin := i
		for i < limit && ((src[i] >= 'a' && src[i] <= 'z') || (src[i] >= 'A' && src[i] <= 'Z') || (src[i] >= '0' && src[i] <= '9') || src[i] == '_' || src[i] == '-') {
			i++
		}
		if i == begin || len(attrs) >= 16 {
			return 0, "", false
		}
		key := src[begin:i]
		if _, ok := attrs[key]; ok {
			return 0, "", false
		}
		space()
		if i >= limit || src[i] != '=' {
			return 0, "", false
		}
		i++
		space()
		if i >= limit || (src[i] != '"' && src[i] != '\'') {
			return 0, "", false
		}
		quote := src[i]
		i++
		var value strings.Builder
		closed := false
		for i < limit {
			b := src[i]
			i++
			if b == quote {
				closed = true
				break
			}
			if b < 32 {
				return 0, "", false
			}
			if b == '\\' {
				if i >= limit || (src[i] != quote && src[i] != '\\') {
					return 0, "", false
				}
				b = src[i]
				i++
			}
			value.WriteByte(b)
		}
		if !closed || i >= limit || !strings.ContainsRune(" \t}", rune(src[i])) {
			return 0, "", false
		}
		attrs[key] = value.String()
	}
	return 0, "", false
}
func citationMarkdown(src string) string {
	const directive = ":codex-file-citation{"
	if !strings.Contains(src, directive) {
		return src
	}
	prefix := "HERDRCITATIONTOKEN"
	for strings.Contains(src, prefix) {
		prefix += "X"
	}
	var tokenized strings.Builder
	citations := []fileCitation{}
	offset := 0
	for scan := 0; scan < len(src); {
		rel := strings.Index(src[scan:], directive)
		if rel < 0 {
			break
		}
		start := scan + rel
		scan = start + len(directive)
		slashes := 0
		for i := start - 1; i >= 0 && src[i] == '\\'; i-- {
			slashes++
		}
		if slashes%2 != 0 {
			continue
		}
		end, p, ok := citationAt(src, start)
		if !ok {
			continue
		}
		token := fmt.Sprintf("%s%dEND", prefix, len(citations))
		citations = append(citations, fileCitation{src[start:end], p, token})
		tokenized.WriteString(src[offset:start])
		tokenized.WriteString(token)
		offset = end
		scan = end
	}
	if len(citations) == 0 {
		return src
	}
	tokenized.WriteString(src[offset:])
	text := tokenized.String()
	doc := parser.New(parser.Default)
	doc.Set(text)
	allowed := map[string]bool{}
	for i := 0; i < doc.Len(); i++ {
		inlines := doc.Inlines(i)
		for j, in := range inlines {
			if in.Kind != parser.Text {
				continue
			}
			nested := false
			for k := 0; k < j; k++ {
				if (inlines[k].Kind == parser.Link || inlines[k].Kind == parser.Image) && j < inlines[k].Next {
					nested = true
					break
				}
			}
			if nested {
				continue
			}
			for _, cite := range citations {
				if strings.Contains(in.Text, cite.token) {
					allowed[cite.token] = true
				}
			}
		}
	}
	replacements := []string{}
	for _, cite := range citations {
		value := cite.raw
		if allowed[cite.token] {
			label := path.Base(cite.path)
			label = strings.NewReplacer("\\", "\\\\", "[", "\\[", "]", "\\]", "*", "\\*", "_", "\\_", "`", "\\`").Replace(label)
			value = "[" + label + "](herdr-file://open?path=" + url.QueryEscape(cite.path) + ")"
		}
		replacements = append(replacements, cite.token, value)
	}
	return strings.NewReplacer(replacements...).Replace(text)
}

// Paths here belong to the remote POSIX workspace, even on a Windows client.
func workspaceReference(cwd, base, raw string) (relative, anchor string, err error) {
	return fileReference(cwd, base, raw, false)
}
func fileReference(cwd, base, raw string, filesystem bool) (relative, anchor string, err error) {
	bad := errors.New("文件不在当前工作区内")
	if strings.HasPrefix(raw, "herdr-file://open?") {
		u, e := url.Parse(raw)
		if e != nil {
			return "", "", bad
		}
		raw = u.Query().Get("path")
	}
	if strings.ContainsAny(raw, "\x00\\") || strings.HasPrefix(raw, "//") || strings.IndexFunc(raw, unicode.IsControl) >= 0 {
		return "", "", bad
	}
	if strings.HasPrefix(raw, "file:") {
		u, e := url.Parse(raw)
		if e != nil || u.Host != "" || u.RawQuery != "" {
			return "", "", bad
		}
		raw = u.Path
		if u.Fragment != "" {
			raw += "#" + u.Fragment
		}
	}
	if u, e := url.Parse(raw); e == nil && u.Scheme != "" {
		return "", "", bad
	}
	p, fragment, _ := strings.Cut(raw, "#")
	anchor = fragment
	if decoded, e := url.PathUnescape(p); e == nil {
		p = decoded
	} else {
		return "", "", bad
	}
	if strings.IndexFunc(p, unicode.IsControl) >= 0 || strings.HasPrefix(p, "//") {
		return "", "", bad
	}
	root := path.Clean(cwd)
	if !path.IsAbs(root) || p == "" || strings.ContainsRune(p, '\\') {
		return "", "", bad
	}
	if filesystem {
		root = "/"
	}
	full := p
	if !path.IsAbs(full) {
		if base == "" {
			full = path.Join(cwd, full)
		} else {
			full = path.Join(root, path.Dir(base), full)
		}
	}
	full = path.Clean(full)
	if filesystem && full == "/" {
		return "", anchor, nil
	}
	prefix := strings.TrimRight(root, "/") + "/"
	if !strings.HasPrefix(full, prefix) || full == root {
		return "", "", bad
	}
	relative = strings.TrimPrefix(full, prefix)
	if strings.ContainsAny(relative, "\x00\\") {
		return "", "", bad
	}
	return relative, anchor, nil
}
