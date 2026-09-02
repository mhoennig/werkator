// Package doctor checks whether this host can run werkdock sandboxes:
// unprivileged user namespaces with a uid-0 mapping and enforced
// read-only root binds, the required CLI tools, and disk/quota headroom
// for the build footprint. It is a port of Werkator's
// werkator-build-prerequisites.sh, with the same PASS/FAIL output.
package doctor

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// MinFreeKiB is the disk footprint a sandbox build needs headroom for:
// unpacked rootfs (zstd expands roughly 3-4x), toolchain caches, build
// output. ~5 GiB, in KiB.
const MinFreeKiB = 5 * 1024 * 1024

// Runner executes a command and returns its combined output; injected
// so the evaluation logic is testable against captured fixtures.
type Runner func(name string, args ...string) (string, error)

// Report is the outcome of all checks.
type Report struct {
	Checks   []Check
	Warnings []string
}

// Check is one PASS/FAIL line.
type Check struct {
	OK  bool
	Msg string
}

func (r *Report) pass(format string, a ...any) {
	r.Checks = append(r.Checks, Check{OK: true, Msg: fmt.Sprintf(format, a...)})
}

func (r *Report) fail(format string, a ...any) {
	r.Checks = append(r.Checks, Check{OK: false, Msg: fmt.Sprintf(format, a...)})
}

func (r *Report) warn(format string, a ...any) {
	r.Warnings = append(r.Warnings, fmt.Sprintf(format, a...))
}

// OK reports whether no check failed.
func (r *Report) OK() bool {
	for _, c := range r.Checks {
		if !c.OK {
			return false
		}
	}
	return true
}

// Run executes all checks against targetDir (where images and build
// workspaces will live).
func Run(targetDir string, selfUID int, run Runner) *Report {
	r := &Report{}
	sandboxChecks(r, selfUID, run)
	toolChecks(r)
	diskChecks(r, targetDir, run)
	return r
}

// sandboxProbe is the command run inside the sandbox; its three output
// lines are the signals evaluated below.
const sandboxProbe = "id -u && cat /proc/self/uid_map && (touch /usr/ro-test 2>&1 || true)"

func sandboxChecks(r *Report, selfUID int, run Runner) {
	if _, err := exec.LookPath("bwrap"); err != nil {
		r.fail("bwrap is not installed on this host")
		return
	}
	version, err := run("bwrap", "--version")
	if err != nil {
		r.fail("bwrap --version failed: %v", err)
		return
	}
	r.pass("bwrap version: %s", strings.TrimSpace(version))
	out, err := run("bwrap",
		"--unshare-user", "--unshare-pid", "--die-with-parent",
		"--uid", "0", "--gid", "0",
		"--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp",
		"sh", "-c", sandboxProbe)
	if err != nil {
		r.fail("bwrap invocation failed (no user namespace support?): %s", strings.TrimSpace(out))
		return
	}
	EvaluateSandbox(r, out, selfUID)
}

// EvaluateSandbox checks the three signals of the sandbox probe output:
// uid 0 inside, a uid_map back to the unprivileged user, and an
// enforced read-only root bind.
func EvaluateSandbox(r *Report, output string, selfUID int) {
	lines := strings.Split(strings.TrimRight(output, "\n"), "\n")
	line := func(i int) string {
		if i < len(lines) {
			return strings.TrimSpace(lines[i])
		}
		return ""
	}
	if line(0) == "0" {
		r.pass("build runs as root inside the namespace (uid 0)")
	} else {
		r.fail("expected uid 0 inside the namespace, got: %s", line(0))
	}
	mapRe := regexp.MustCompile(`^\s*0\s+` + strconv.Itoa(selfUID) + `\s+1`)
	if mapRe.MatchString(line(1)) {
		r.pass("uid_map maps root back to the unprivileged user (uid %d)", selfUID)
	} else {
		r.fail("expected uid_map '0 %d 1', got: %s", selfUID, line(1))
	}
	if strings.Contains(strings.ToLower(output), "read-only file system") {
		r.pass("read-only root bind is enforced")
	} else {
		r.fail("the read-only root bind did not reject a write to /usr")
	}
}

func toolChecks(r *Report) {
	if _, err := exec.LookPath("tar"); err != nil {
		r.fail("tar is not installed — required to unpack images")
	} else {
		r.pass("tar is available")
	}
	if _, err := exec.LookPath("zstd"); err != nil {
		r.warn("zstd is not installed — .tar.zst images cannot be unpacked")
	}
}

func diskChecks(r *Report, targetDir string, run Runner) {
	minGiB := MinFreeKiB / 1024 / 1024
	homeFS := ""
	if home, err := os.UserHomeDir(); err == nil {
		if out, err := run("df", "-Pk", home); err == nil {
			homeFS, _, _ = ParseDF(out)
		}
	}
	out, err := run("df", "-Pk", targetDir)
	if err != nil {
		r.warn("could not measure free space on %s — only the quota check applies", targetDir)
		return
	}
	device, availKiB, mount := ParseDF(out)
	if device == "" {
		r.warn("could not measure free space on %s — only the quota check applies", targetDir)
	} else {
		if homeFS != "" && device != homeFS {
			r.warn("target dir is on %s (mounted at %s), not the home filesystem (%s) — builds will run on slower storage", device, mount, homeFS)
		}
		if availKiB < MinFreeKiB {
			r.fail("less than %d GiB free space on the build working filesystem (%s)", minGiB, mount)
		} else {
			r.pass("at least %d GiB free space on the build working filesystem (%s, device %s)", minGiB, mount, device)
		}
	}
	quotaOut, err := run("quota", "-g")
	if err != nil || strings.TrimSpace(quotaOut) == "" {
		r.warn("no readable group quota tooling on this host — only free space was checked")
		return
	}
	lines := ParseQuota(quotaOut)
	if len(lines) == 0 {
		r.warn("quota tooling present but no group quota lines could be parsed — only free space was checked")
		return
	}
	ok := true
	detail := ""
	for _, q := range lines {
		// Only the quota of the target filesystem counts — other
		// volumes may legitimately be full without affecting builds.
		if device != "" && filepath.Base(q.FS) != filepath.Base(device) && q.FS != device {
			continue
		}
		headroom := q.Limit - q.Blocks
		if headroom < MinFreeKiB {
			ok = false
			detail += fmt.Sprintf(" %s: %.1f GiB free of quota;", filepath.Base(q.FS), float64(headroom)/1024/1024)
		}
	}
	if ok {
		r.pass("group quota headroom covers the %d GiB build footprint", minGiB)
	} else {
		r.fail("group quota headroom below the %d GiB build footprint; raise the quota before building.%s", minGiB, detail)
	}
}

// ParseDF extracts device, available KiB, and mount point from
// `df -Pk DIR` output.
func ParseDF(output string) (device string, availKiB int64, mount string) {
	lines := strings.Split(strings.TrimSpace(output), "\n")
	if len(lines) < 2 {
		return "", 0, ""
	}
	fields := strings.Fields(lines[1])
	if len(fields) < 6 {
		return "", 0, ""
	}
	avail, err := strconv.ParseInt(fields[3], 10, 64)
	if err != nil {
		return "", 0, ""
	}
	return fields[0], avail, fields[5]
}

// QuotaLine is one filesystem's group quota: used blocks and the hard
// limit, both in KiB.
type QuotaLine struct {
	FS     string
	Blocks int64
	Limit  int64
}

// ParseQuota parses `quota -g` output, including the wrapped form where
// a long device name stands alone on its own line and the numbers
// follow on the next. A '*' suffix on the blocks value (over soft
// quota) is ignored.
func ParseQuota(output string) []QuotaLine {
	var result []QuotaLine
	pendingFS := ""
	for _, raw := range strings.Split(output, "\n") {
		fields := strings.Fields(raw)
		if len(fields) == 0 {
			continue
		}
		if len(fields) == 1 && strings.HasPrefix(fields[0], "/") {
			pendingFS = fields[0]
			continue
		}
		if strings.HasPrefix(fields[0], "/") && len(fields) >= 4 {
			if blocks, limit, ok := quotaNumbers(fields[1], fields[3]); ok {
				result = append(result, QuotaLine{FS: fields[0], Blocks: blocks, Limit: limit})
				pendingFS = ""
			}
			continue
		}
		if pendingFS != "" && len(fields) >= 3 {
			if blocks, limit, ok := quotaNumbers(fields[0], fields[2]); ok {
				result = append(result, QuotaLine{FS: pendingFS, Blocks: blocks, Limit: limit})
				pendingFS = ""
			}
		}
	}
	return result
}

func quotaNumbers(blocksField, limitField string) (int64, int64, bool) {
	blocks, err := strconv.ParseInt(strings.TrimSuffix(blocksField, "*"), 10, 64)
	if err != nil {
		return 0, 0, false
	}
	limit, err := strconv.ParseInt(limitField, 10, 64)
	if err != nil {
		return 0, 0, false
	}
	return blocks, limit, true
}

// Render writes the report in the PASS/FAIL format of the original
// prerequisites script, ending with a RESULT line.
func (r *Report) Render(w io.Writer) {
	for _, c := range r.Checks {
		status := "PASS"
		if !c.OK {
			status = "FAIL"
		}
		fmt.Fprintf(w, "%s: %s\n", status, c.Msg)
	}
	for _, warning := range r.Warnings {
		fmt.Fprintf(w, "WARNING: %s\n", warning)
	}
	passed := 0
	for _, c := range r.Checks {
		if c.OK {
			passed++
		}
	}
	fmt.Fprintln(w)
	if r.OK() {
		fmt.Fprintf(w, "RESULT: PASS (%d/%d) — werkdock sandboxes are usable on this host.\n", passed, len(r.Checks))
	} else {
		fmt.Fprintf(w, "RESULT: FAIL (%d/%d) — werkdock sandboxes are not usable on this host.\n", passed, len(r.Checks))
	}
}
