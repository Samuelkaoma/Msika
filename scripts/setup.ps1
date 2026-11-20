# Run once after cloning. Turns on the pre-push hook.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
git config core.hooksPath .githooks
Write-Host "Git hooks enabled: core.hooksPath = $(git config core.hooksPath)"
