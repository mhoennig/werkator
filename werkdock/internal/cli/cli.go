// Package cli parses werkdock's docker-shaped command line (RFC 0002)
// and dispatches to the internal packages. Exit codes follow docker:
// 125 for werkdock's own errors, otherwise the sandboxed command's code
// is passed through.
package cli

import (
	"fmt"
	"io"
	"os"
)

// Version is replaced at release time; the dev default marks unreleased
// builds.
var Version = "0.1.0-dev"

const exitCLIError = 125

// Main runs the CLI and returns the process exit code.
func Main(args []string) int {
	if len(args) == 0 {
		usage(os.Stderr)
		return exitCLIError
	}
	switch args[0] {
	case "run":
		return runCmd(args[1:])
	case "load":
		return loadCmd(args[1:])
	case "images":
		return imagesCmd(args[1:])
	case "doctor":
		return doctorCmd(args[1:])
	case "version", "--version":
		fmt.Printf("werkdock %s\n", Version)
		return 0
	case "help", "--help", "-h":
		usage(os.Stdout)
		return 0
	default:
		fmt.Fprintf(os.Stderr, "werkdock: unknown command %q\n\n", args[0])
		usage(os.Stderr)
		return exitCLIError
	}
}

func usage(w io.Writer) {
	fmt.Fprint(w, `werkdock — a docker-like sandbox CLI over bwrap, filesystem isolation only.
Network, uid, /proc, /dev, and /tmp come from the host by contract.

Usage:
  werkdock run [flags] IMAGE COMMAND [ARG...]   run a command in a sandbox
  werkdock load -i ARCHIVE [--name NAME]        import a rootfs archive as an image
  werkdock images                               list loaded images, one name per line
  werkdock doctor [TARGET_DIR]                  check whether this host can run sandboxes
  werkdock version                              print the version

Run flags:
  -v, --volume SRC:DEST[:ro]   bind mount (repeatable; -v and --tmpfs apply in flag order)
      --tmpfs DEST             empty tmpfs at DEST (repeatable)
  -e, --env KEY=VALUE          set an environment variable (KEY alone copies it from the host)
  -w, --workdir DIR            working directory inside the sandbox (default /)
      --rm                     remove the instance afterwards (currently required)

The store lives in $WERKDOCK_HOME (default ~/.werkdock).
`)
}

func fail(err error) int {
	fmt.Fprintf(os.Stderr, "werkdock: %v\n", err)
	return exitCLIError
}
