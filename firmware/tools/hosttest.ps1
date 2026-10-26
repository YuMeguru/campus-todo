# Host tests for the firmware's pure logic.
#
# ASCII only on purpose: Windows PowerShell 5.1 decodes .ps1 as the ANSI codepage,
# so non-ASCII text in this file breaks the parser before anything runs.
#
# Why Zig instead of cc: this machine has no gcc/clang/cc, and MSVC is installed
# without the Windows SDK (no stdio.h), so it cannot build a host program at all.
# Zig bundles clang plus mingw headers and needs no SDK -- one zip on D: and done.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools/hosttest.ps1

$ErrorActionPreference = 'Stop'

$candidates = @(
    'D:\Tools\zig\zig.exe',
    'D:\Tools\zig-x86_64-windows\zig.exe'
)
$zig = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $zig) {
    $found = Get-ChildItem 'D:\Tools' -Recurse -Filter 'zig.exe' -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($found) { $zig = $found.FullName }
}
if (-not $zig) {
    throw 'zig.exe not found. Extract Zig under D:\Tools first (see README).'
}

$out = Join-Path $env:TEMP 'campus_host_test.exe'
$sources = @(
    'tests/test_campus_logic.c',
    'main/campus_frame.c',
    'main/campus_snapshot.c',
    'main/campus_model.c'
)

Write-Host "[hosttest] compiler: $zig"
& $zig cc -std=c11 -Wall -Wextra -Werror -I main @sources -o $out
if ($LASTEXITCODE -ne 0) {
    throw "compile failed with exit code $LASTEXITCODE"
}
& $out
if ($LASTEXITCODE -ne 0) {
    throw "tests failed with exit code $LASTEXITCODE"
}
