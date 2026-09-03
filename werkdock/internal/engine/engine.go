// Package engine executes sandboxed commands. The CLI verbs are thin
// frontends over this package, so a later daemon can expose the same
// logic without duplicating it (RFC 0002).
package engine

// MountMode distinguishes the mount kinds a RunSpec can carry.
type MountMode int

const (
	// MountBind is a read-write bind mount.
	MountBind MountMode = iota
	// MountRoBind is a read-only bind mount.
	MountRoBind
	// MountTmpfs is an empty tmpfs at Dest; Source is unused.
	MountTmpfs
)

// Mount is one mount, applied in order; later mounts shadow earlier
// ones at their own path, exactly as bwrap layers them — the order of
// -v and --tmpfs flags is therefore significant and preserved.
type Mount struct {
	Mode   MountMode
	Source string
	Dest   string
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
	Mounts  []Mount
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
