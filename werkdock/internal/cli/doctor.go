package cli

import (
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"

	"werkdock/internal/doctor"
	"werkdock/internal/store"
)

func doctorCmd(args []string) int {
	fs := flag.NewFlagSet("doctor", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	if err := fs.Parse(args); err != nil {
		return fail(err)
	}
	targetDir := ""
	switch len(fs.Args()) {
	case 0:
		st, err := store.Default()
		if err != nil {
			return fail(err)
		}
		targetDir = st.Root
		// The store may not exist yet; measure its closest existing
		// ancestor, which sits on the same filesystem.
		for {
			if _, err := os.Stat(targetDir); err == nil {
				break
			}
			parent := filepath.Dir(targetDir)
			if parent == targetDir {
				break
			}
			targetDir = parent
		}
	case 1:
		targetDir = fs.Args()[0]
	default:
		return fail(fmt.Errorf("unexpected argument %q", fs.Args()[1]))
	}
	report := doctor.Run(targetDir, os.Getuid(), runCombined)
	report.Render(os.Stdout)
	if report.OK() {
		return 0
	}
	return 1
}

func runCombined(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return string(out), err
}
