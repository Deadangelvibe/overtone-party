# Helper: authenticate GitHub CLI and push the Overtone Party repository.
# Run: powershell -ExecutionPolicy Bypass -File .\push-to-github.ps1
$ErrorActionPreference = 'Stop'

$git = Join-Path $env:LOCALAPPDATA 'Programs\PortableGit\cmd\git.exe'
$gh  = Join-Path $env:LOCALAPPDATA 'Programs\GitHubCLI\bin\gh.exe'

Set-Location $PSScriptRoot

# 1. Authenticate GitHub CLI if needed.
& $gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'GitHub CLI is not authenticated. Opening browser login...'
    & $gh auth login --web --git-protocol https
    if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed.' }
}

# 2. Add remote if missing.
$url = & $git remote get-url origin 2>$null
if (-not $url) {
    $url = Read-Host 'Enter GitHub repository URL (https://github.com/<user>/<repo>.git)'
    if ([string]::IsNullOrWhiteSpace($url)) { throw 'Repository URL is empty.' }
    & $git remote add origin $url
}

# 3. Push branch and all version tags.
& $git push -u origin main --tags
