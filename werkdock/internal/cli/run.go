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
	Volumes []engine.Bind
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
		Binds:   hostBinds(opts.Volumes),
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

// hostBinds prepends the host mounts the contract prescribes: DNS comes
// from the host, so /etc/resolv.conf is bound read-only when it exists —
// before the user binds, so an explicit bind over /etc wins.
func hostBinds(volumes []engine.Bind) []engine.Bind {
	var binds []engine.Bind
	if fi, err := os.Stat("/etc/resolv.conf"); err == nil && fi.Mode().IsRegular() {
		binds = append(binds, engine.Bind{Source: "/etc/resolv.conf", Dest: "/etc/resolv.conf", ReadOnly: true})
	}
	return append(binds, volumes...)
}

// parseRun parses the docker-shaped run flags. Docker flags whose
// promise werkdock cannot keep are registered and refused with a
// reason — never silently ignored (RFC 0002).
func parseRun(args []string, getenv func(string) string) (*runOptions, error) {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	var volumes, envs stringList
	opts := &runOptions{}
	fs.Var(&volumes, "v", "bind mount SRC:DEST[:ro]")
	fs.Var(&volumes, "volume", "bind mount SRC:DEST[:ro]")
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
	for _, v := range volumes {
		bind, err := parseVolume(v)
		if err != nil {
			return nil, err
		}
		opts.Volumes = append(opts.Volumes, bind)
	}
	for _, e := range envs {
		opts.Env = append(opts.Env, parseEnv(e, getenv))
	}
	if opts.Workdir != "" && !filepath.IsAbs(opts.Workdir) {
		return nil, fmt.Errorf("workdir must be an absolute path: %s", opts.Workdir)
	}
	return opts, nil
}

func parseVolume(v string) (engine.Bind, error) {
	parts := strings.Split(v, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return engine.Bind{}, fmt.Errorf("invalid volume %q, expected SRC:DEST[:ro]", v)
	}
	bind := engine.Bind{Source: parts[0], Dest: parts[1]}
	if len(parts) == 3 {
		if parts[2] != "ro" {
			return engine.Bind{}, fmt.Errorf("invalid volume option %q in %q, only 'ro' is supported", parts[2], v)
		}
		bind.ReadOnly = true
	}
	if !filepath.IsAbs(bind.Source) {
		return engine.Bind{}, fmt.Errorf("volume source must be an absolute path: %s", bind.Source)
	}
	if !filepath.IsAbs(bind.Dest) {
		return engine.Bind{}, fmt.Errorf("volume destination must be an absolute path: %s", bind.Dest)
	}
	return bind, nil
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
