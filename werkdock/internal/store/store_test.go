package store

import (
	"archive/tar"
	"compress/gzip"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// writeTestArchive builds a minimal rootfs .tar.gz with the stdlib, so
// the tests need no zstd; Load unpacks it with the system tar.
func writeTestArchive(t *testing.T, path string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	gz := gzip.NewWriter(f)
	tw := tar.NewWriter(gz)
	if err := tw.WriteHeader(&tar.Header{Name: "etc/", Mode: 0o755, Typeflag: tar.TypeDir}); err != nil {
		t.Fatal(err)
	}
	content := []byte("hello from the rootfs\n")
	if err := tw.WriteHeader(&tar.Header{Name: "etc/hello", Mode: 0o644, Size: int64(len(content))}); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write(content); err != nil {
		t.Fatal(err)
	}
	for _, c := range []interface{ Close() error }{tw, gz, f} {
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestLoadUnpacksArchiveIntoTheStore(t *testing.T) {
	if _, err := exec.LookPath("tar"); err != nil {
		t.Skip("tar not installed")
	}
	st := Store{Root: t.TempDir()}
	archive := filepath.Join(t.TempDir(), "mini-rootfs.tar.gz")
	writeTestArchive(t, archive)
	if err := st.Load(archive, "mini"); err != nil {
		t.Fatal(err)
	}
	rootfs, err := st.RootFS("mini")
	if err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(rootfs, "etc", "hello"))
	if err != nil || string(content) != "hello from the rootfs\n" {
		t.Errorf("unpacked file: %q, %v", content, err)
	}
	metaRaw, err := os.ReadFile(filepath.Join(st.Root, "images", "mini", "image.json"))
	if err != nil {
		t.Fatal(err)
	}
	var meta ImageMeta
	if err := json.Unmarshal(metaRaw, &meta); err != nil {
		t.Fatal(err)
	}
	if meta.Name != "mini" || meta.Source == "" || meta.CreatedAt.IsZero() {
		t.Errorf("image.json incomplete: %+v", meta)
	}
}

func TestLoadRefusesAnExistingImageName(t *testing.T) {
	if _, err := exec.LookPath("tar"); err != nil {
		t.Skip("tar not installed")
	}
	st := Store{Root: t.TempDir()}
	archive := filepath.Join(t.TempDir(), "mini.tar.gz")
	writeTestArchive(t, archive)
	if err := st.Load(archive, "mini"); err != nil {
		t.Fatal(err)
	}
	err := st.Load(archive, "mini")
	if err == nil || !strings.Contains(err.Error(), "already exists") {
		t.Errorf("got %v, want an already-exists refusal", err)
	}
}

func TestLoadLeavesNoHalfImageOnFailure(t *testing.T) {
	if _, err := exec.LookPath("tar"); err != nil {
		t.Skip("tar not installed")
	}
	st := Store{Root: t.TempDir()}
	broken := filepath.Join(t.TempDir(), "broken.tar.gz")
	if err := os.WriteFile(broken, []byte("this is not a tar archive"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := st.Load(broken, "broken"); err == nil {
		t.Fatal("expected the load to fail")
	}
	if _, err := os.Stat(filepath.Join(st.Root, "images", "broken")); !os.IsNotExist(err) {
		t.Errorf("expected no image directory, got %v", err)
	}
	if _, err := os.Stat(filepath.Join(st.Root, "images", "broken.tmp")); !os.IsNotExist(err) {
		t.Errorf("expected no leftover tmp directory, got %v", err)
	}
}

func TestRootFSValidation(t *testing.T) {
	st := Store{Root: t.TempDir()}
	if _, err := st.RootFS("no-such-image"); err == nil || !strings.Contains(err.Error(), "no such image") {
		t.Errorf("got %v, want a no-such-image error", err)
	}
	if _, err := st.RootFS("../escape"); err == nil || !strings.Contains(err.Error(), "invalid image name") {
		t.Errorf("got %v, want an invalid-name error", err)
	}
}

func TestListNamesLoadedImagesAndIgnoresTmpLeftovers(t *testing.T) {
	st := Store{Root: t.TempDir()}
	if names, err := st.List(); err != nil || names != nil {
		t.Fatalf("empty store: got %v, %v", names, err)
	}
	for _, dir := range []string{"beta", "alpha", "broken.tmp"} {
		if err := os.MkdirAll(filepath.Join(st.Root, "images", dir), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	names, err := st.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(names) != 2 || names[0] != "alpha" || names[1] != "beta" {
		t.Errorf("got %v, want [alpha beta]", names)
	}
}

func TestImageNameFromArchive(t *testing.T) {
	tests := []struct{ in, want string }{
		{"werkator-buildenv-trixie.tar.zst", "werkator-buildenv-trixie"},
		{"/path/to/Base.TAR.GZ", "base"},
		{"rootfs.tgz", "rootfs"},
		{"plain", "plain"},
	}
	for _, tt := range tests {
		if got := ImageNameFromArchive(tt.in); got != tt.want {
			t.Errorf("ImageNameFromArchive(%q) = %q, want %q", tt.in, got, tt.want)
		}
	}
}
