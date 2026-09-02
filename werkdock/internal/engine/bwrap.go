package engine

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Bwrap runs a RunSpec through the bwrap CLI — filesystem isolation
// only; network, uid mapping target, /proc, /dev, and /tmp come from
// the host by contract.
//
// The invocation is a port of Werkator's BwrapBuildRunner, including
// the parts hardened on a real Hostsharing webspace: bind mountpoints
// are pre-created inside the rootfs (a plain host directory), because
// bwrap cannot mkdir them against the read-only root bind.
type Bwrap struct {
	// Path of the bwrap binary; empty means "bwrap" via PATH.
	Path string
	// Stdio of the sandboxed command; nil fields default to the
	// werkdock process's own.
	Stdout io.Writer
	Stderr io.Writer
	Stdin  io.Reader
}

// DefaultPATH is the PATH inside the sandbox; the environment is
// cleared (docker semantics), so a sane default must be set explicitly.
const DefaultPATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

// Argv assembles the full bwrap command line for spec.
//
// Mount order: the rootfs first; then /proc, /dev, and the tmpfs
// mounts for /tmp and /root, BEFORE the user binds, so a bind whose
// destination lies below them lands inside instead of being shadowed;
// then the user binds in the given order.
func (b *Bwrap) Argv(spec RunSpec) ([]string, error) {
	if spec.RootFS == "" {
		return nil, errors.New("rootfs must be set")
	}
	if !filepath.IsAbs(spec.RootFS) {
		return nil, fmt.Errorf("rootfs must be an absolute path: %s", spec.RootFS)
	}
	if len(spec.Command) == 0 {
		return nil, errors.New("no command specified")
	}
	bin := b.Path
	if bin == "" {
		bin = "bwrap"
	}
	args := []string{
		bin,
		"--unshare-user",
		"--unshare-pid",
		"--die-with-parent",
		"--uid", "0",
		"--gid", "0",
		"--ro-bind", spec.RootFS, "/",
		"--proc", "/proc",
		"--dev", "/dev",
		"--tmpfs", "/tmp",
		"--tmpfs", "/root",
	}
	for _, m := range spec.Mounts {
		if !filepath.IsAbs(m.Dest) {
			return nil, fmt.Errorf("mount destination must be an absolute path: %s", m.Dest)
		}
		switch m.Mode {
		case MountBind:
			args = append(args, "--bind", m.Source, m.Dest)
		case MountRoBind:
			args = append(args, "--ro-bind", m.Source, m.Dest)
		case MountTmpfs:
			args = append(args, "--tmpfs", m.Dest)
		default:
			return nil, fmt.Errorf("unknown mount mode %d for %s", m.Mode, m.Dest)
		}
	}
	args = append(args,
		"--clearenv",
		"--setenv", "HOME", "/root",
		"--setenv", "PATH", DefaultPATH,
	)
	for _, e := range spec.Env {
		args = append(args, "--setenv", e.Key, e.Value)
	}
	workdir := spec.Workdir
	if workdir == "" {
		workdir = "/"
	}
	args = append(args, "--chdir", workdir, "--")
	args = append(args, spec.Command...)
	return args, nil
}

// EnsureMountpoints pre-creates the mountpoints of spec inside the
// rootfs directory. bwrap creates mountpoints against the sandbox view,
// which is the read-only rootfs bind — every destination missing from
// the rootfs fails with "Read-only file system". The rootfs directory
// itself is a plain host directory, so the mountpoints are created
// there; bwrap then finds them and has nothing left to mkdir.
//
// Anything that already exists in the rootfs is left alone (e.g.
// /etc/resolv.conf is a file many rootfs archives ship). A bind whose
// source is a regular file gets a file mountpoint, not a directory.
func EnsureMountpoints(spec RunSpec) error {
	for _, dest := range []string{"/proc", "/dev", "/tmp", "/root"} {
		if err := ensureDir(spec.RootFS, dest); err != nil {
			return err
		}
	}
	for _, m := range spec.Mounts {
		target, err := rootfsPath(spec.RootFS, m.Dest)
		if err != nil {
			return err
		}
		if _, err := os.Lstat(target); err == nil {
			continue
		}
		if m.Mode == MountTmpfs {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		src, err := os.Stat(m.Source)
		if err != nil {
			return fmt.Errorf("bind source %s: %w", m.Source, err)
		}
		if src.Mode().IsRegular() {
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return err
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o644)
			if err != nil {
				return err
			}
			if err := f.Close(); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(target, 0o755); err != nil {
			return err
		}
	}
	return nil
}

func ensureDir(rootfs, dest string) error {
	target, err := rootfsPath(rootfs, dest)
	if err != nil {
		return err
	}
	if _, statErr := os.Lstat(target); statErr == nil {
		return nil
	}
	return os.MkdirAll(target, 0o755)
}

// rootfsPath resolves dest inside rootfs and refuses destinations that
// escape it — werkdock assembles mounts from user input, so this must
// hold even for hostile paths.
func rootfsPath(rootfs, dest string) (string, error) {
	root := filepath.Clean(rootfs)
	target := filepath.Join(root, dest)
	prefix := root
	if !strings.HasSuffix(prefix, string(filepath.Separator)) {
		prefix += string(filepath.Separator)
	}
	if target != root && !strings.HasPrefix(target, prefix) {
		return "", fmt.Errorf("bind destination escapes the rootfs: %s", dest)
	}
	return target, nil
}

// Run executes spec and returns the command's exit code; bwrap
// propagates the child's code, so the caller can pass it through.
func (b *Bwrap) Run(spec RunSpec) (int, error) {
	argv, err := b.Argv(spec)
	if err != nil {
		return 0, err
	}
	if err := EnsureMountpoints(spec); err != nil {
		return 0, err
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Stdout = b.Stdout
	if cmd.Stdout == nil {
		cmd.Stdout = os.Stdout
	}
	cmd.Stderr = b.Stderr
	if cmd.Stderr == nil {
		cmd.Stderr = os.Stderr
	}
	cmd.Stdin = b.Stdin
	err = cmd.Run()
	if err == nil {
		return 0, nil
	}
	var exit *exec.ExitError
	if errors.As(err, &exit) {
		return exit.ExitCode(), nil
	}
	return 0, err
}
