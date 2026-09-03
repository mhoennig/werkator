package cli

import (
	"reflect"
	"strings"
	"testing"

	"werkdock/internal/engine"
)

func noEnv(string) string { return "" }

func TestParseRunSupportedFlags(t *testing.T) {
	opts, err := parseRun([]string{
		"--rm",
		"-v", "/repo:/repo",
		"--volume", "/cache:/root/.gradle:ro",
		"-e", "CI=true",
		"-w", "/repo",
		"buildenv", "sh", "-c", "./gradlew build",
	}, noEnv)
	if err != nil {
		t.Fatal(err)
	}
	if opts.Image != "buildenv" {
		t.Errorf("image: got %q", opts.Image)
	}
	if !reflect.DeepEqual(opts.Command, []string{"sh", "-c", "./gradlew build"}) {
		t.Errorf("command: got %q", opts.Command)
	}
	wantMounts := []engine.Mount{
		{Mode: engine.MountBind, Source: "/repo", Dest: "/repo"},
		{Mode: engine.MountRoBind, Source: "/cache", Dest: "/root/.gradle"},
	}
	if !reflect.DeepEqual(opts.Mounts, wantMounts) {
		t.Errorf("mounts: got %+v", opts.Mounts)
	}
	if !reflect.DeepEqual(opts.Env, []engine.EnvVar{{Key: "CI", Value: "true"}}) {
		t.Errorf("env: got %+v", opts.Env)
	}
	if opts.Workdir != "/repo" {
		t.Errorf("workdir: got %q", opts.Workdir)
	}
}

func TestParseRunCopiesBareEnvKeysFromTheHost(t *testing.T) {
	getenv := func(key string) string {
		if key == "LANG" {
			return "C.UTF-8"
		}
		return ""
	}
	opts, err := parseRun([]string{"--rm", "-e", "LANG", "img", "true"}, getenv)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(opts.Env, []engine.EnvVar{{Key: "LANG", Value: "C.UTF-8"}}) {
		t.Errorf("env: got %+v", opts.Env)
	}
}

func TestParseRunRefusesDockerFlagsLoudly(t *testing.T) {
	tests := []struct {
		args       []string
		wantReason string
	}{
		{[]string{"--rm", "-p", "8080:80", "img", "true"}, "no network isolation"},
		{[]string{"--rm", "--network", "host", "img", "true"}, "network is the host's"},
		{[]string{"--rm", "--memory", "1g", "img", "true"}, "does not manage resources"},
		{[]string{"--rm", "--user", "1000", "img", "true"}, "uid 0 mapped to the calling user"},
		{[]string{"--rm", "-d", "img", "true"}, "not implemented yet"},
	}
	for _, tt := range tests {
		t.Run(strings.Join(tt.args, " "), func(t *testing.T) {
			_, err := parseRun(tt.args, noEnv)
			if err == nil || !strings.Contains(err.Error(), tt.wantReason) {
				t.Errorf("got %v, want refusal containing %q", err, tt.wantReason)
			}
		})
	}
}

func TestParseRunRequiresRmForNow(t *testing.T) {
	_, err := parseRun([]string{"img", "true"}, noEnv)
	if err == nil || !strings.Contains(err.Error(), "--rm") {
		t.Errorf("got %v, want the --rm requirement", err)
	}
}

func TestParseRunValidation(t *testing.T) {
	tests := []struct {
		name    string
		args    []string
		wantErr string
	}{
		{"no image", []string{"--rm"}, "no image specified"},
		{"no command", []string{"--rm", "img"}, "no command specified"},
		{"volume without dest", []string{"--rm", "-v", "/only-src", "img", "true"}, "expected SRC:DEST"},
		{"volume with bad option", []string{"--rm", "-v", "/a:/b:cached", "img", "true"}, "only 'ro' and 'rw' are supported"},
		{"relative tmpfs dest", []string{"--rm", "--tmpfs", "rel", "img", "true"}, "absolute"},
		{"relative volume source", []string{"--rm", "-v", "rel:/b", "img", "true"}, "absolute"},
		{"relative volume dest", []string{"--rm", "-v", "/a:rel", "img", "true"}, "absolute"},
		{"relative workdir", []string{"--rm", "-w", "rel", "img", "true"}, "absolute"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := parseRun(tt.args, noEnv)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("got %v, want it to contain %q", err, tt.wantErr)
			}
		})
	}
}

func TestParseRunKeepsMountFlagOrderAcrossVolumeAndTmpfs(t *testing.T) {
	// The git-metadata mask depends on it: ro-bind .git, tmpfs over
	// .git/werkator, then the workspace bind — in exactly this order.
	opts, err := parseRun([]string{
		"--rm",
		"-v", "/r/.git:/r/.git:ro",
		"--tmpfs", "/r/.git/werkator",
		"-v", "/r/ws:/r/ws",
		"img", "true",
	}, noEnv)
	if err != nil {
		t.Fatal(err)
	}
	want := []engine.Mount{
		{Mode: engine.MountRoBind, Source: "/r/.git", Dest: "/r/.git"},
		{Mode: engine.MountTmpfs, Dest: "/r/.git/werkator"},
		{Mode: engine.MountBind, Source: "/r/ws", Dest: "/r/ws"},
	}
	if !reflect.DeepEqual(opts.Mounts, want) {
		t.Errorf("mounts: got %+v", opts.Mounts)
	}
}

func TestParseRunAcceptsTheExplicitRwVolumeOption(t *testing.T) {
	opts, err := parseRun([]string{"--rm", "-v", "/a:/b:rw", "img", "true"}, noEnv)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(opts.Mounts, []engine.Mount{{Mode: engine.MountBind, Source: "/a", Dest: "/b"}}) {
		t.Errorf("mounts: got %+v", opts.Mounts)
	}
}

func TestParseRunStopsFlagParsingAtTheImage(t *testing.T) {
	// Docker semantics: everything after the image belongs to the
	// command, even if it looks like a flag.
	opts, err := parseRun([]string{"--rm", "img", "ls", "-la", "/tmp"}, noEnv)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(opts.Command, []string{"ls", "-la", "/tmp"}) {
		t.Errorf("command: got %q", opts.Command)
	}
}
