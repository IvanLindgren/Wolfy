// wolfy-updater запускается отдельно от Wolfy: ждёт закрытия старого процесса,
// ставит уже проверенный MSI и открывает новую версию. Сам файл перед запуском
// копируется во временный каталог, поэтому MSI может заменить его в установке.
package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func main() {
	pid := flag.Int("wait-pid", 0, "process to wait for")
	msi := flag.String("msi", "", "verified MSI package")
	launch := flag.String("launch", "", "application launcher")
	flag.Parse()

	if *pid <= 0 || !regularMSI(*msi) {
		os.Exit(2)
	}
	waitForExit(*pid, 90*time.Second)

	command := exec.Command("msiexec.exe", "/i", *msi, "/passive", "/norestart")
	err := command.Run()
	code := 0
	if err != nil {
		if exitError, ok := err.(*exec.ExitError); ok {
			code = exitError.ExitCode()
		} else {
			code = -1
		}
	}
	// 3010 означает успешную установку, после которой Windows советует
	// перезагрузить систему; для перезапуска самого Wolfy это не препятствие.
	if code != 0 && code != 3010 {
		os.Exit(3)
	}
	if *launch != "" {
		if info, err := os.Stat(*launch); err == nil && info.Mode().IsRegular() {
			_ = exec.Command(*launch).Start()
		}
	}
}

func regularMSI(path string) bool {
	if !strings.EqualFold(filepath.Ext(path), ".msi") {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular() && info.Size() > 0
}

func waitForExit(pid int, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		filter := fmt.Sprintf("PID eq %s", strconv.Itoa(pid))
		output, err := exec.Command("tasklist.exe", "/FI", filter, "/FO", "CSV", "/NH").Output()
		if err != nil || !strings.Contains(string(output), fmt.Sprintf(`"%d"`, pid)) {
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
}
