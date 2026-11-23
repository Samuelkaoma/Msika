# The local quality gate (Windows). The same checks run in CI (see CONTRIBUTING.md).
#   ./scripts/check.ps1 --fast   unit tests, lint, typecheck (about a minute)
#   ./scripts/check.ps1          everything: also integration tests (need Docker) and full builds
param([string]$Mode = "")

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($Mode -ne "" -and $Mode -ne "--fast") {
    Write-Error "usage: scripts/check.ps1 [--fast]"
    exit 2
}
$fast = ($Mode -eq "--fast")

function Step([string]$Title) { Write-Host "`n==> $Title" }

# Run a native command and stop at the first failure.
function Run([string]$Exe, [string[]]$CommandArgs) {
    & $Exe @CommandArgs
    if ($LASTEXITCODE -ne 0) { Write-Error "$Exe failed (exit $LASTEXITCODE)"; exit $LASTEXITCODE }
}

Step "Java: compile with lint warnings as errors, and unit tests"
Run ".\gradlew.bat" @("test")

Step "Web: install and typecheck"
Run "pnpm" @("install", "--frozen-lockfile")
Run "pnpm" @("-r", "--if-present", "typecheck")

Step "Web: unit tests"
Run "pnpm" @("-r", "--if-present", "test")

if (-not $fast) {
    Step "Java: integration tests and full build"
    Run ".\gradlew.bat" @("build")

    Step "Web: full build"
    Run "pnpm" @("-r", "build")
}

if ($fast) { Write-Host "`nAll checks passed (fast)." } else { Write-Host "`nAll checks passed." }
