package cli

import (
	"flag"
	"fmt"
	"io"

	"werkdock/internal/store"
)

// imagesCmd prints the loaded image names, one per line — machine-usable
// (Werkator checks image existence through it) and close enough to
// `docker images --format '{{.Repository}}'`.
func imagesCmd(args []string) int {
	fs := flag.NewFlagSet("images", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	if err := fs.Parse(args); err != nil {
		return fail(err)
	}
	if len(fs.Args()) != 0 {
		return fail(fmt.Errorf("unexpected argument %q", fs.Args()[0]))
	}
	st, err := store.Default()
	if err != nil {
		return fail(err)
	}
	names, err := st.List()
	if err != nil {
		return fail(err)
	}
	for _, name := range names {
		fmt.Println(name)
	}
	return 0
}
