#Requires -Version 5.1
# Minimal wrapper stub used by launch_wave9_step_a.ps1 offline tests.
# Advanced script with [CmdletBinding()] so named splatting via hashtable
# (@WrapperParameters) works correctly -- matching the real wrapper's signature.
[CmdletBinding()]
param(
    [string] $EvidenceOutput = ''
)
# When STUB_CAPTURE is set, append the observed EvidenceOutput value so the
# test can assert the launcher forwarded the correct path.
$captureFile = [System.Environment]::GetEnvironmentVariable('STUB_CAPTURE')
if (-not [string]::IsNullOrEmpty($captureFile)) {
    $line = "stub-wrapper-noop EvidenceOutput=[$EvidenceOutput]"
    [System.IO.File]::AppendAllText($captureFile, $line + "`n")
}
exit 0
