param(
  [switch]$Install
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$VenvPath = Join-Path $ProjectRoot ".venv"
$ActivatePath = Join-Path $VenvPath "Scripts\Activate.ps1"
$RequirementsPath = Join-Path $ProjectRoot "requirements.txt"

if (-not (Test-Path $VenvPath)) {
  python -m venv $VenvPath
}

. $ActivatePath

if ($Install -and (Test-Path $RequirementsPath)) {
  python -m pip install -r $RequirementsPath
}

Write-Host "Virtual environment active: $VenvPath"
