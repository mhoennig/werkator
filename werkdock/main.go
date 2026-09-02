package main

import (
	"os"

	"werkdock/internal/cli"
)

func main() {
	os.Exit(cli.Main(os.Args[1:]))
}
