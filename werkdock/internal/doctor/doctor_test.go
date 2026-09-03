package doctor

import (
	"reflect"
	"strings"
	"testing"
)

func TestEvaluateSandboxAllSignalsPass(t *testing.T) {
	r := &Report{}
	output := "0\n         0     120957          1\ntouch: cannot touch '/usr/ro-test': Read-only file system\n"
	EvaluateSandbox(r, output, 120957)
	if !r.OK() {
		t.Errorf("expected all signals to pass, got %+v", r.Checks)
	}
	if len(r.Checks) != 3 {
		t.Errorf("expected 3 checks, got %d", len(r.Checks))
	}
}

func TestEvaluateSandboxFailures(t *testing.T) {
	tests := []struct {
		name     string
		output   string
		selfUID  int
		wantFail string
	}{
		{
			"not root inside",
			"1000\n         0     120957          1\nRead-only file system\n",
			120957,
			"expected uid 0",
		},
		{
			"uid_map maps someone else",
			"0\n         0     999999          1\nRead-only file system\n",
			120957,
			"expected uid_map",
		},
		{
			"writable root bind",
			"0\n         0     120957          1\n",
			120957,
			"did not reject a write",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			r := &Report{}
			EvaluateSandbox(r, tt.output, tt.selfUID)
			found := false
			for _, c := range r.Checks {
				if !c.OK && strings.Contains(c.Msg, tt.wantFail) {
					found = true
				}
			}
			if !found {
				t.Errorf("expected a failing check containing %q, got %+v", tt.wantFail, r.Checks)
			}
		})
	}
}

func TestParseDF(t *testing.T) {
	output := "Filesystem     1024-blocks      Used Available Capacity Mounted on\n" +
		"/dev/mapper/vg0-home  959786032 447013936 463941300      50% /home\n"
	device, avail, mount := ParseDF(output)
	if device != "/dev/mapper/vg0-home" || avail != 463941300 || mount != "/home" {
		t.Errorf("got %q %d %q", device, avail, mount)
	}
	if d, a, m := ParseDF("garbage"); d != "" || a != 0 || m != "" {
		t.Errorf("expected empty result for garbage, got %q %d %q", d, a, m)
	}
}

func TestParseQuotaPlainAndWrappedLines(t *testing.T) {
	output := `Disk quotas for group g123456 (gid 123456):
     Filesystem  blocks   quota   limit   grace   files   quota   limit   grace
/dev/vdb1        123456  900000 1000000            1234       0       0
/dev/mapper/very-long-device-name-that-wraps
                654321* 4500000 5000000            4321       0       0
`
	want := []QuotaLine{
		{FS: "/dev/vdb1", Blocks: 123456, Limit: 1000000},
		{FS: "/dev/mapper/very-long-device-name-that-wraps", Blocks: 654321, Limit: 5000000},
	}
	if got := ParseQuota(output); !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v\nwant %+v", got, want)
	}
}

func TestParseQuotaIgnoresUnparsableOutput(t *testing.T) {
	if got := ParseQuota("no quotas here\n"); len(got) != 0 {
		t.Errorf("expected no lines, got %+v", got)
	}
}

// fakeRunner serves canned outputs keyed by command name.
func fakeRunner(outputs map[string]string) Runner {
	return func(name string, args ...string) (string, error) {
		return outputs[name], nil
	}
}

func TestDiskChecksFailOnQuotaHeadroomOfTheTargetFilesystem(t *testing.T) {
	r := &Report{}
	// 1 GiB quota headroom on the home device, plenty on another one.
	outputs := map[string]string{
		"df": "Filesystem 1024-blocks Used Available Capacity Mounted on\n" +
			"/dev/vdb1 100000000 10000000 90000000 10% /home\n",
		"quota": "Disk quotas for group g1 (gid 1):\n" +
			"     Filesystem  blocks   quota   limit   grace\n" +
			"/dev/vdb1 4000000 5000000 5048576 - - -\n" +
			"/dev/other 0 0 99999999 - - -\n",
	}
	diskChecks(r, "/home/user", fakeRunner(outputs))
	if r.OK() {
		t.Fatalf("expected the quota check to fail, got %+v", r.Checks)
	}
	failing := ""
	for _, c := range r.Checks {
		if !c.OK {
			failing = c.Msg
		}
	}
	if !strings.Contains(failing, "quota headroom below") || !strings.Contains(failing, "vdb1") {
		t.Errorf("unexpected failure message: %s", failing)
	}
}

func TestDiskChecksPassWithSpaceAndQuota(t *testing.T) {
	r := &Report{}
	outputs := map[string]string{
		"df": "Filesystem 1024-blocks Used Available Capacity Mounted on\n" +
			"/dev/vdb1 100000000 10000000 90000000 10% /home\n",
		"quota": "Disk quotas for group g1 (gid 1):\n" +
			"     Filesystem  blocks   quota   limit   grace\n" +
			"/dev/vdb1 1000000 90000000 99000000 - - -\n",
	}
	diskChecks(r, "/home/user", fakeRunner(outputs))
	if !r.OK() {
		t.Errorf("expected disk checks to pass, got %+v", r.Checks)
	}
	if len(r.Checks) != 2 {
		t.Errorf("expected free-space and quota checks, got %+v", r.Checks)
	}
}

func TestRenderEndsWithTheResultLine(t *testing.T) {
	r := &Report{}
	r.pass("all good")
	r.warn("just saying")
	var out strings.Builder
	r.Render(&out)
	rendered := out.String()
	if !strings.Contains(rendered, "PASS: all good\n") ||
		!strings.Contains(rendered, "WARNING: just saying\n") ||
		!strings.Contains(rendered, "RESULT: PASS (1/1)") {
		t.Errorf("unexpected rendering:\n%s", rendered)
	}
}
