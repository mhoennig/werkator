package cli

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"werkdock/internal/engine"
	"werkdock/internal/store"
)

// runOptions is the parsed form of `werkdock run` flags, separated from
// execution so the parsing is testable and a later daemon can reuse it.
type runOptions struct {
	Mounts  []engine.Mount
	Env     []engine.EnvVar
	Workdir string
	Remove  bool
	Image   string
	Command []string
}

func runCmd(args []string) int {
	opts, err := parseRun(args, os.Getenv)
	if err != nil {
		return fail(err)
	}
	st, err := store.Default()
	if err != nil {
		return fail(err)
	}
	rootfs, err := st.RootFS(opts.Image)
	if err != nil {
		return fail(err)
	}
	spec := engine.RunSpec{
		RootFS:  rootfs,
		Mounts:  hostMounts(opts.Mounts),
		Env:     opts.Env,
		Workdir: opts.Workdir,
		Command: opts.Command,
	}
	eng := &engine.Bwrap{}
	code, err := eng.Run(spec)
	if err != nil {
		return fail(err)
	}
	return code
}

// hostMounts prepends the host mounts the contract prescribes: DNS comes
// from the host, so /etc/resolv.conf is bound read-only when it exists —
// before the user mounts, so an explicit mount over /etc wins.
func hostMounts(mounts []engine.Mount) []engine.Mount {
	var all []engine.Mount
	if fi, err := os.Stat("/etc/resolv.conf"); err == nil && fi.Mode().IsRegular() {
		all = append(all, engine.Mount{Mode: engine.MountRoBind, Source: "/etc/resolv.conf", Dest: "/etc/resolv.conf"})
	}
	return append(all, mounts...)
}

// parseRun parses the docker-shaped run flags. Docker flags whose
// promise werkdock cannot keep are registered and refused with a
// reason — never silently ignored (RFC 0002).
func parseRun(args []string, getenv func(string) string) (*runOptions, error) {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	var envs stringList
	opts := &runOptions{}
	// -v and --tmpfs collect into ONE ordered list: bwrap layers mounts in
	// order, so a tmpfs between two binds (the git-metadata mask) must stay
	// between them.
	volumes := &mountFlag{mounts: &opts.Mounts}
	tmpfs := &mountFlag{mounts: &opts.Mounts, tmpfs: true}
	fs.Var(volumes, "v", "bind mount SRC:DEST[:ro]")
	fs.Var(volumes, "volume", "bind mount SRC:DEST[:ro]")
	fs.Var(tmpfs, "tmpfs", "empty tmpfs at DEST")
	fs.Var(&envs, "e", "environment variable KEY=VALUE")
	fs.Var(&envs, "env", "environment variable KEY=VALUE")
	fs.StringVar(&opts.Workdir, "w", "", "working directory inside the sandbox")
	fs.StringVar(&opts.Workdir, "workdir", "", "working directory inside the sandbox")
	fs.BoolVar(&opts.Remove, "rm", false, "remove the instance afterwards")
	refuse(fs, "p", "werkdock has no network isolation; the sandbox binds host ports directly")
	refuse(fs, "publish", "werkdock has no network isolation; the sandbox binds host ports directly")
	refuse(fs, "network", "the network is the host's by contract; there is nothing to configure")
	refuse(fs, "memory", "werkdock does not manage resources; use the host's limits (e.g. systemd)")
	refuse(fs, "cpus", "werkdock does not manage resources; use the host's limits (e.g. systemd)")
	refuse(fs, "user", "the sandbox always runs uid 0 mapped to the calling user")
	refuse(fs, "d", "detached instances are not implemented yet")
	refuse(fs, "detach", "detached instances are not implemented yet")
	if err := fs.Parse(args); err != nil {
		return nil, err
	}
	if !opts.Remove {
		return nil, errors.New("persistent instances are not implemented yet; run with --rm")
	}
	rest := fs.Args()
	if len(rest) == 0 {
		return nil, errors.New("no image specified")
	}
	if len(rest) == 1 {
		return nil, errors.New("no command specified (werkdock images carry no default command yet)")
	}
	opts.Image = rest[0]
	opts.Command = rest[1:]
	for _, e := range envs {
		opts.Env = append(opts.Env, parseEnv(e, getenv))
	}
	if opts.Workdir != "" && !filepath.IsAbs(opts.Workdir) {
		return nil, fmt.Errorf("workdir must be an absolute path: %s", opts.Workdir)
	}
	return opts, nil
}

func parseVolume(v string) (engine.Mount, error) {
	parts := strings.Split(v, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return engine.Mount{}, fmt.Errorf("invalid volume %q, expected SRC:DEST[:ro]", v)
	}
	mount := engine.Mount{Mode: engine.MountBind, Source: parts[0], Dest: parts[1]}
	if len(parts) == 3 {
		switch parts[2] {
		case "ro":
			mount.Mode = engine.MountRoBind
		case "rw":
			// docker accepts :rw as the explicit default; so do we
		default:
			return engine.Mount{}, fmt.Errorf("invalid volume option %q in %q, only 'ro' and 'rw' are supported", parts[2], v)
		}
	}
	if !filepath.IsAbs(mount.Source) {
		return engine.Mount{}, fmt.Errorf("volume source must be an absolute path: %s", mount.Source)
	}
	if !filepath.IsAbs(mount.Dest) {
		return engine.Mount{}, fmt.Errorf("volume destination must be an absolute path: %s", mount.Dest)
	}
	return mount, nil
}

// mountFlag appends -v/--volume and --tmpfs values to one shared,
// ordered mount list.
type mountFlag struct {
	mounts *[]engine.Mount
	tmpfs  bool
}

func (f *mountFlag) String() string { return "" }

func (f *mountFlag) Set(v string) error {
	if f.tmpfs {
		if !filepath.IsAbs(v) {
			return fmt.Errorf("tmpfs destination must be an absolute path: %s", v)
		}
		*f.mounts = append(*f.mounts, engine.Mount{Mode: engine.MountTmpfs, Dest: v})
		return nil
	}
	mount, err := parseVolume(v)
	if err != nil {
		return err
	}
	*f.mounts = append(*f.mounts, mount)
	return nil
}

func parseEnv(e string, getenv func(string) string) engine.EnvVar {
	if key, value, found := strings.Cut(e, "="); found {
		return engine.EnvVar{Key: key, Value: value}
	}
	return engine.EnvVar{Key: e, Value: getenv(e)}
}

// stringList collects a repeatable flag's values in order.
type stringList []string

func (s *stringList) String() string { return strings.Join(*s, ",") }

func (s *stringList) Set(v string) error {
	*s = append(*s, v)
	return nil
}

// refusedFlag rejects a known docker flag with the reason werkdock
// cannot honor it.
type refusedFlag struct {
	name   string
	reason string
}

func (f *refusedFlag) String() string { return "" }

func (f *refusedFlag) Set(string) error {
	return fmt.Errorf("flag -%s is not supported: %s", f.name, f.reason)
}

func (f *refusedFlag) IsBoolFlag() bool { return true }

func refuse(fs *flag.FlagSet, name, reason string) {
	fs.Var(&refusedFlag{name: name, reason: reason}, name, reason)
}
