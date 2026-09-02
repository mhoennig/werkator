// Package engine executes sandboxed commands. The CLI verbs are thin
// frontends over this package, so a later daemon can expose the same
// logic without duplicating it (RFC 0002).
package engine

// Bind is one bind mount, applied in order; later mounts shadow earlier
// ones at their own path, exactly as bwrap layers them.
type Bind struct {
	Source   string
	Dest     string
	ReadOnly bool
}

// EnvVar is one environment variable; order is preserved.
type EnvVar struct {
	Key   string
	Value string
}

// RunSpec describes one sandboxed command, independent of the engine
// that executes it.
type RunSpec struct {
	// RootFS is the absolute path to the unpacked image rootfs,
	// bound read-only at /.
	RootFS  string
	Binds   []Bind
	Env     []EnvVar
	Workdir string
	Command []string
}

// Engine runs a RunSpec and reports the command's exit code.
// bwrap is the first engine; native namespaces may become a second
// (RFC 0001).
type Engine interface {
	Run(spec RunSpec) (int, error)
}
