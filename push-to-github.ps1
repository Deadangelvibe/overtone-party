# Helper: authenticate GitHub CLI and push the Overtone Party repository.
# Run: powershell -ExecutionPolicy Bypass -File .\push-to-github.ps1

$git = Join-Path $env:LOCALAPPDATA 'Programs\PortableGit\cmd\git.exe'
$gh  = Join-Path $env:LOCALAPPDATA 'Programs\GitHubCLI\bin\gh.exe'

Set-Location $PSScriptRoot

# 1. Authenticate GitHub CLI if needed.
cmd /c "`"$gh`" auth status >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host 'GitHub CLI is not authenticated. Opening browser login...'
    & $gh auth login --web --git-protocol https
    if ($LASTEXITCODE -ne 0) { Write-Host 'GitHub login failed.'; exit 1 }
}

# 2. Add remote if missing.
$url = cmd /c "`"$git`" remote get-url origin 2>nul"
if ([string]::IsNullOrWhiteSpace($url)) {
    $url = Read-Host 'Enter GitHub repository URL (https://github.com/<user>/<repo>.git)'
    if ([string]::IsNullOrWhiteSpace($url)) { Write-Host 'Repository URL is empty.'; exit 1 }
    & $git remote add origin $url
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

# 3. Push branch and all version tags.
& $git push -u origin main --tags
