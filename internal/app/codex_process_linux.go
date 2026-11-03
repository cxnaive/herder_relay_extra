package app

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

func codexProcessStart(pid int) (string, error) {
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", err
	}
	end := strings.LastIndexByte(string(stat), ')')
	if end < 0 {
		return "", errCodexProcessStart
	}
	fields := strings.Fields(string(stat[end+1:]))
	if len(fields) < 20 {
		return "", errCodexProcessStart
	}
	ticks, err := strconv.ParseUint(fields[19], 10, 64)
	if err != nil || ticks == 0 {
		return "", errCodexProcessStart
	}
	boot, err := os.ReadFile("/proc/sys/kernel/random/boot_id")
	if err != nil || !codexBindingID.MatchString(strings.TrimSpace(string(boot))) {
		return "", errCodexProcessStart
	}
	return fmt.Sprintf("%s:%d:%d", strings.TrimSpace(string(boot)), pid, ticks), nil
}
