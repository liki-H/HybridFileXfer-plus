param([switch]$CoreOnly)

$ErrorActionPreference = 'Stop'
# Resume self test runner (contract section 9.2 / 9.4)
#
#   -CoreOnly : compile only core/ + nio/ + shim + the pure-core test cases.
#   (default) : compile the whole PC source tree and run every case (T1..T7).
#
# Build output goes to %TEMP% so the working tree stays clean.

$proj = Split-Path -Parent $PSScriptRoot
if ($CoreOnly) {
    $out = Join-Path $env:TEMP 'hfx-resume-core-selftest'
} else {
    $out = Join-Path $env:TEMP 'hfx-resume-selftest'
}
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory (Join-Path $out 'classes') -Force | Out-Null
New-Item -ItemType Directory (Join-Path $out 'test-classes') -Force | Out-Null
$classes = Join-Path $out 'classes'
$tclasses = Join-Path $out 'test-classes'
$shim = Join-Path $PSScriptRoot 'shim\org\jetbrains\annotations\NotNull.java'

if ($CoreOnly) {
    $src = @(Get-ChildItem (Join-Path $proj 'src\top\weixiansen574\hybridfilexfer\core') -Recurse -Filter *.java | % FullName) + @(Get-ChildItem (Join-Path $proj 'src\top\weixiansen574\nio') -Recurse -Filter *.java | % FullName)
} else {
    $src = @(Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | % FullName)
}
Write-Host ("compiling {0} production files (CoreOnly={1})" -f $src.Count, $CoreOnly)
& javac -encoding UTF-8 -nowarn -d $classes @($src + $shim)
if ($LASTEXITCODE -ne 0) { throw "production compile failed" }

$tests = @(Get-ChildItem $PSScriptRoot -Recurse -Filter *.java | ? { $_.FullName -notlike '*\shim\*' } | % FullName)
& javac -encoding UTF-8 -nowarn -cp $classes -d $tclasses @tests
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

& java -cp "$classes;$tclasses" top.weixiansen574.hybridfilexfer.ResumeSelfTest
if ($LASTEXITCODE -ne 0) { throw "SELFTEST FAIL (exit=$LASTEXITCODE)" }
Write-Host "SELFTEST PASS"
