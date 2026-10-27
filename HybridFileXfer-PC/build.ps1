# Build HybridFileXfer-PC into a runnable jar (no Android SDK / no Gradle needed).
# Usage:  powershell -ExecutionPolicy Bypass -File HybridFileXfer-PC\build.ps1
# Output: HybridFileXfer-PC\HybridFileXfer.jar   (put next to start.exe)
$ErrorActionPreference = 'Stop'
$pc = Split-Path -Parent $MyInvocation.MyCommand.Path
$build = Join-Path $env:TEMP 'hfx-pc-build'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null

# The 3 bean classes import org.jetbrains.annotations.NotNull, but src/lib/annotations-24.0.1.jar
# is not shipped in the repo. Compile an equivalent stub @interface (CLASS retention, no runtime impact).
$stubDir = Join-Path $build 'stub\org\jetbrains\annotations'
New-Item -ItemType Directory -Path $stubDir -Force | Out-Null
$stub = Join-Path $stubDir 'NotNull.java'
$stubCode = "package org.jetbrains.annotations;`r`npublic @interface NotNull {`r`n}`r`n"
[IO.File]::WriteAllText($stub, $stubCode, (New-Object Text.UTF8Encoding $false))

$srcs = @()
$srcs += Get-ChildItem (Join-Path $pc 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcs += $stub
Write-Output ("SOURCES: " + $srcs.Count)

& javac -encoding UTF-8 -nowarn -d $classes $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
Write-Output "JAVAC OK"

Get-ChildItem (Join-Path $pc 'src') -Filter 'messages_*.properties' | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $classes $_.Name) -Force
}

$jarPath = Join-Path $pc 'HybridFileXfer.jar'
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
$manifest = Join-Path $pc 'src\META-INF\MANIFEST.MF'
& jar cfm $jarPath $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }

Write-Output ("JAR: " + $jarPath)
Write-Output ("SIZE: " + (Get-Item $jarPath).Length)
