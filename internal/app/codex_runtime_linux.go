package app

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func codexProcessListeners(pid int) (map[string]string, error) {
	own, err := os.Readlink("/proc/self/ns/net")
	if err != nil {
		return nil, err
	}
	other, err := os.Readlink(fmt.Sprintf("/proc/%d/ns/net", pid))
	if err != nil || own != other {
		return nil, errCodexProcessStart
	}
	return codexListenersAt("/proc", pid)
}

func codexListenersAt(proc string, pid int) (map[string]string, error) {
	if pid <= 0 {
		return nil, errCodexProcessStart
	}
	base := filepath.Join(proc, strconv.Itoa(pid))
	fds, err := os.ReadDir(filepath.Join(base, "fd"))
	if err != nil {
		return nil, err
	}
	if len(fds) > 4096 {
		return nil, errCodexProcessStart
	}
	inodes := map[string]bool{}
	for _, fd := range fds {
		link, err := os.Readlink(filepath.Join(base, "fd", fd.Name()))
		if err != nil {
			continue
		}
		if strings.HasPrefix(link, "socket:[") && strings.HasSuffix(link, "]") {
			inodes[strings.TrimSuffix(strings.TrimPrefix(link, "socket:["), "]")] = true
		}
	}
	file, err := os.Open(filepath.Join(base, "net", "tcp"))
	if err != nil {
		return nil, err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	result := map[string]string{}
	for n := 0; scanner.Scan(); n++ {
		if n > 65536 {
			return nil, errCodexProcessStart
		}
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 || fields[3] != "0A" || !inodes[fields[9]] {
			continue
		}
		local := strings.Split(fields[1], ":")
		if len(local) != 2 || local[0] != "0100007F" {
			continue
		}
		port, err := strconv.ParseUint(local[1], 16, 16)
		if err != nil || port == 0 {
			continue
		}
		origin := fmt.Sprintf("http://127.0.0.1:%d", port)
		if result[origin] != "" {
			return nil, errCodexProcessStart
		}
		result[origin] = fields[9]
	}
	return result, scanner.Err()
}
