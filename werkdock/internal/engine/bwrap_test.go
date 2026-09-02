package engine

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestArgvAssemblesTheHardenedInvocation(t *testing.T) {
	b := &Bwrap{}
	spec := RunSpec{
		RootFS: "/store/images/buildenv/rootfs",
		Binds: []Bind{
			{Source: "/etc/resolv.conf", Dest: "/etc/resolv.conf", ReadOnly: true},
			{Source: "/repo", Dest: "/repo"},
			{Source: "/cache", Dest: "/root/.gradle"},
		},
		Env:     []EnvVar{{Key: "CI", Value: "true"}, {Key: "TERM", Value: "dumb"}},
		Workdir: "/repo",
		Command: []string{"/bin/sh", "-c", "./gradlew build"},
	}
	argv, err := b.Argv(spec)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"bwrap",
		"--unshare-user", "--unshare-pid", "--die-with-parent",
		"--uid", "0", "--gid", "0",
		"--ro-bind", "/store/images/buildenv/rootfs", "/",
		"--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/root",
		"--ro-bind", "/etc/resolv.conf", "/etc/resolv.conf",
		"--bind", "/repo", "/repo",
		"--bind", "/cache", "/root/.gradle",
		"--clearenv",
		"--setenv", "HOME", "/root",
		"--setenv", "PATH", DefaultPATH,
		"--setenv", "CI", "true",
		"--setenv", "TERM", "dumb",
		"--chdir", "/repo", "--",
		"/bin/sh", "-c", "./gradlew build",
	}
	if !reflect.DeepEqual(argv, want) {
		t.Errorf("argv mismatch:\n got %q\nwant %q", argv, want)
	}
}

func TestArgvValidation(t *testing.T) {
	tests := []struct {
		name    string
		spec    RunSpec
		wantErr string
	}{
		{"missing rootfs", RunSpec{Command: []string{"true"}}, "rootfs must be set"},
		{"relative rootfs", RunSpec{RootFS: "rootfs", Command: []string{"true"}}, "absolute"},
		{"missing command", RunSpec{RootFS: "/r"}, "no command specified"},
		{
			"relative bind dest",
			RunSpec{RootFS: "/r", Binds: []Bind{{Source: "/s", Dest: "work"}}, Command: []string{"true"}},
			"absolute",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := (&Bwrap{}).Argv(tt.spec)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("got error %v, want it to contain %q", err, tt.wantErr)
			}
		})
	}
}

func TestArgvDefaultsWorkdirToRoot(t *testing.T) {
	argv, err := (&Bwrap{}).Argv(RunSpec{RootFS: "/r", Command: []string{"true"}})
	if err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(argv, " ")
	if !strings.Contains(joined, "--chdir / --") {
		t.Errorf("expected default workdir /, got: %s", joined)
	}
}

func TestEnsureMountpointsCreatesMissingAndSkipsExisting(t *testing.T) {
	rootfs := t.TempDir()
	// The rootfs ships /etc/resolv.conf as a file with content — it
	// must be left alone.
	if err := os.MkdirAll(filepath.Join(rootfs, "etc"), 0o755); err != nil {
		t.Fatal(err)
	}
	shipped := filepath.Join(rootfs, "etc", "resolv.conf")
	if err := os.WriteFile(shipped, []byte("nameserver 127.0.0.53\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	srcDir := t.TempDir()
	srcFile := filepath.Join(srcDir, "hosts")
	if err := os.WriteFile(srcFile, []byte("127.0.0.1 localhost\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	spec := RunSpec{
		RootFS: rootfs,
		Binds: []Bind{
			{Source: "/etc", Dest: "/etc/resolv.conf", ReadOnly: true}, // exists: skipped (source type irrelevant)
			{Source: srcDir, Dest: "/repo/workspace"},                  // missing dir mountpoint
			{Source: srcFile, Dest: "/etc/hosts.werkdock"},             // missing file mountpoint
		},
		Command: []string{"true"},
	}
	if err := EnsureMountpoints(spec); err != nil {
		t.Fatal(err)
	}
	for _, dir := range []string{"proc", "dev", "tmp", "root", "repo/workspace"} {
		fi, err := os.Stat(filepath.Join(rootfs, dir))
		if err != nil || !fi.IsDir() {
			t.Errorf("expected directory mountpoint %s in the rootfs: %v", dir, err)
		}
	}
	fi, err := os.Stat(filepath.Join(rootfs, "etc", "hosts.werkdock"))
	if err != nil || !fi.Mode().IsRegular() {
		t.Errorf("expected file mountpoint etc/hosts.werkdock in the rootfs: %v", err)
	}
	content, err := os.ReadFile(shipped)
	if err != nil || string(content) != "nameserver 127.0.0.53\n" {
		t.Errorf("shipped rootfs file was modified: %q, %v", content, err)
	}
}

func TestEnsureMountpointsRefusesEscapingDestinations(t *testing.T) {
	spec := RunSpec{
		RootFS:  t.TempDir(),
		Binds:   []Bind{{Source: "/tmp", Dest: "/../outside"}},
		Command: []string{"true"},
	}
	err := EnsureMountpoints(spec)
	if err == nil || !strings.Contains(err.Error(), "escapes the rootfs") {
		t.Errorf("got %v, want an escape refusal", err)
	}
}

// TestRunInsideRealSandbox is the gated integration test: it runs only
// where bwrap and unprivileged user namespaces actually work. The host
// / serves as the read-only rootfs, so nothing is unpacked and (all
// mountpoints existing) nothing is written.
func TestRunInsideRealSandbox(t *testing.T) {
	if _, err := exec.LookPath("bwrap"); err != nil {
		t.Skip("bwrap not installed")
	}
	if err := exec.Command("bwrap", "--unshare-user", "--uid", "0", "--ro-bind", "/", "/", "true").Run(); err != nil {
		t.Skipf("unprivileged user namespaces not usable here: %v", err)
	}
	var stdout, stderr bytes.Buffer
	b := &Bwrap{Stdout: &stdout, Stderr: &stderr}
	code, err := b.Run(RunSpec{
		RootFS:  "/",
		Command: []string{"id", "-u"},
	})
	if err != nil {
		t.Fatalf("run failed: %v (stderr: %s)", err, stderr.String())
	}
	if code != 0 {
		t.Fatalf("exit code %d, stderr: %s", code, stderr.String())
	}
	if got := strings.TrimSpace(stdout.String()); got != "0" {
		t.Errorf("expected uid 0 inside the sandbox, got %q", got)
	}
}

func TestRunPassesTheExitCodeThrough(t *testing.T) {
	if _, err := exec.LookPath("bwrap"); err != nil {
		t.Skip("bwrap not installed")
	}
	if err := exec.Command("bwrap", "--unshare-user", "--uid", "0", "--ro-bind", "/", "/", "true").Run(); err != nil {
		t.Skipf("unprivileged user namespaces not usable here: %v", err)
	}
	b := &Bwrap{Stdout: &bytes.Buffer{}, Stderr: &bytes.Buffer{}}
	code, err := b.Run(RunSpec{RootFS: "/", Command: []string{"sh", "-c", "exit 42"}})
	if err != nil {
		t.Fatal(err)
	}
	if code != 42 {
		t.Errorf("expected exit code 42, got %d", code)
	}
}
