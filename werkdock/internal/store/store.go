// Package store is the on-disk image store. An image is a rootfs
// archive unpacked under the store root; instance state will live here
// too once persistent instances exist, in a format both the CLI and a
// later daemon can read (RFC 0002).
package store

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// Store is rooted at $WERKDOCK_HOME, defaulting to ~/.werkdock.
type Store struct {
	Root string
}

// ImageMeta is written as image.json beside each image's rootfs.
type ImageMeta struct {
	Name      string    `json:"name"`
	Source    string    `json:"source"`
	CreatedAt time.Time `json:"createdAt"`
}

var nameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]*$`)

// Default resolves the store root from the environment.
func Default() (Store, error) {
	if root := os.Getenv("WERKDOCK_HOME"); root != "" {
		return Store{Root: root}, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return Store{}, fmt.Errorf("cannot resolve the store root: %w", err)
	}
	return Store{Root: filepath.Join(home, ".werkdock")}, nil
}

func (s Store) imageDir(name string) string {
	return filepath.Join(s.Root, "images", name)
}

// RootFS resolves an image name to its unpacked rootfs directory.
func (s Store) RootFS(name string) (string, error) {
	if !nameRe.MatchString(name) {
		return "", fmt.Errorf("invalid image name: %q", name)
	}
	rootfs := filepath.Join(s.imageDir(name), "rootfs")
	if fi, err := os.Stat(rootfs); err != nil || !fi.IsDir() {
		return "", fmt.Errorf("no such image: %s (load it with: werkdock load -i ARCHIVE --name %s)", name, name)
	}
	return rootfs, nil
}

// List returns the names of all loaded images, sorted; half-written
// `.tmp` directories from an interrupted load are not images.
func (s Store) List() ([]string, error) {
	entries, err := os.ReadDir(filepath.Join(s.Root, "images"))
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var names []string
	for _, e := range entries {
		if e.IsDir() && nameRe.MatchString(e.Name()) && !strings.HasSuffix(e.Name(), ".tmp") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)
	return names, nil
}

// Load imports a rootfs archive as an image. The archive is unpacked
// with the tar CLI (compression auto-detected; .tar.zst needs the zstd
// binary, which doctor checks) into a temporary directory and renamed
// into place, so a failed load leaves no half image behind.
func (s Store) Load(archive, name string) error {
	if !nameRe.MatchString(name) {
		return fmt.Errorf("invalid image name: %q (allowed: lowercase letters, digits, '.', '_', '-')", name)
	}
	// ".tmp" is the staging suffix of this very function — a legal-looking
	// image name ending in it would collide with interrupted loads.
	if strings.HasSuffix(name, ".tmp") {
		return fmt.Errorf("invalid image name: %q (the .tmp suffix is reserved for staging)", name)
	}
	archiveAbs, err := filepath.Abs(archive)
	if err != nil {
		return err
	}
	if _, err := os.Stat(archiveAbs); err != nil {
		return fmt.Errorf("archive: %w", err)
	}
	dir := s.imageDir(name)
	if _, err := os.Stat(dir); err == nil {
		return fmt.Errorf("image %q already exists (remove %s to replace it)", name, dir)
	}
	tmp := dir + ".tmp"
	if err := os.RemoveAll(tmp); err != nil {
		return err
	}
	rootfs := filepath.Join(tmp, "rootfs")
	if err := os.MkdirAll(rootfs, 0o755); err != nil {
		return err
	}
	cmd := exec.Command("tar", "--no-same-owner", "-xf", archiveAbs, "-C", rootfs)
	if out, err := cmd.CombinedOutput(); err != nil {
		_ = os.RemoveAll(tmp)
		return fmt.Errorf("unpacking %s failed: %w\n%s", archiveAbs, err, strings.TrimSpace(string(out)))
	}
	meta, err := json.MarshalIndent(ImageMeta{Name: name, Source: archiveAbs, CreatedAt: time.Now().UTC()}, "", "  ")
	if err != nil {
		_ = os.RemoveAll(tmp)
		return err
	}
	if err := os.WriteFile(filepath.Join(tmp, "image.json"), append(meta, '\n'), 0o644); err != nil {
		_ = os.RemoveAll(tmp)
		return err
	}
	if err := os.Rename(tmp, dir); err != nil {
		_ = os.RemoveAll(tmp)
		return err
	}
	return nil
}

// ImageNameFromArchive derives a default image name from an archive
// file name by stripping the compression and tar extensions:
// "werkator-buildenv-trixie.tar.zst" becomes "werkator-buildenv-trixie".
func ImageNameFromArchive(archive string) string {
	name := filepath.Base(archive)
	for {
		ext := filepath.Ext(name)
		switch strings.ToLower(ext) {
		case ".tar", ".gz", ".tgz", ".zst", ".xz", ".bz2":
			name = strings.TrimSuffix(name, ext)
		default:
			return strings.ToLower(name)
		}
	}
}
