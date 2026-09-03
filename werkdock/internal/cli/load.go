package cli

import (
	"errors"
	"flag"
	"fmt"
	"io"

	"werkdock/internal/store"
)

func loadCmd(args []string) int {
	fs := flag.NewFlagSet("load", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	var input, name string
	fs.StringVar(&input, "i", "", "rootfs archive to import")
	fs.StringVar(&input, "input", "", "rootfs archive to import")
	fs.StringVar(&name, "name", "", "image name (default: derived from the archive file name)")
	if err := fs.Parse(args); err != nil {
		return fail(err)
	}
	if input == "" {
		return fail(errors.New("load needs -i ARCHIVE"))
	}
	if len(fs.Args()) != 0 {
		return fail(fmt.Errorf("unexpected argument %q", fs.Args()[0]))
	}
	if name == "" {
		name = store.ImageNameFromArchive(input)
	}
	st, err := store.Default()
	if err != nil {
		return fail(err)
	}
	if err := st.Load(input, name); err != nil {
		return fail(err)
	}
	fmt.Printf("Loaded image: %s\n", name)
	return 0
}
