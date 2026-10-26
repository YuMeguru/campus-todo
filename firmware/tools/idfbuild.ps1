# Build wrapper for this firmware project.
#
# ASCII only (see hosttest.ps1 for why).
#
# Why this exists: the repo's own .esp-bootstrap/activate-idf.ps1 relies on
# `activate.py --export`, which emits a dot-source of a generated temp script.
# This machine's execution policy blocks script files, so that fails and idf.py
# never reaches PATH. Here we rebuild the same environment without executing any
# generated script: idf_tools.py prints KEY=VALUE pairs and we assign them.
#
# Usage: powershell -ExecutionPolicy Bypass -File tools/idfbuild.ps1 [idf.py args...]
#        (no args = build)

$ErrorActionPreference = 'Stop'

$env:IDF_PATH = if ($env:IDF_PATH) { $env:IDF_PATH } else { 'D:\DSH\esp\esp-idf-v5.5.3' }
$env:IDF_TOOLS_PATH = if ($env:IDF_TOOLS_PATH) { $env:IDF_TOOLS_PATH } else { 'C:\Users\22050\.espressif' }
$pythonEnv = Join-Path $env:IDF_TOOLS_PATH 'python_env\idf5.5_py3.11_env'
$py = Join-Path $pythonEnv 'Scripts\python.exe'

if (-not (Test-Path $py)) {
    throw "ESP-IDF python env not found at $py"
}

$exported = & $py "$env:IDF_PATH\tools\idf_tools.py" export --format key-value
foreach ($line in $exported) {
    if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        Set-Item -Path ("env:" + $matches[1]) -Value $matches[2]
    }
}
# Without this idf.py prints a warning on every run.
$env:IDF_PYTHON_ENV_PATH = $pythonEnv

$idfArgs = @($args)
if ($idfArgs.Count -eq 0) { $idfArgs = @('build') }

Write-Host ("[idfbuild] idf.py " + ($idfArgs -join ' '))
& $py "$env:IDF_PATH\tools\idf.py" @idfArgs
exit $LASTEXITCODE
