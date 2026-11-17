# Помощник для публикации репозитория Overtone Party на GitHub.
# Запуск: powershell -ExecutionPolicy Bypass -File .\push-to-github.ps1
$ErrorActionPreference = 'Stop'

$git = 'C:\Users\Александр\AppData\Local\Programs\PortableGit\cmd\git.exe'
$gh  = 'C:\Users\Александр\AppData\Local\Programs\GitHubCLI\bin\gh.exe'

Set-Location $PSScriptRoot

# 1. Авторизация GitHub CLI (если ещё не выполнено).
& $gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host 'GitHub CLI не авторизован. Открываю вход через браузер...'
    & $gh auth login --web --git-protocol https
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось авторизоваться в GitHub.' }
}

# 2. Удаление репозитория (если ещё не добавлен).
$url = & $git remote get-url origin 2>$null
if (-not $url) {
    $url = Read-Host 'Введите URL репозитория GitHub (https://github.com/<user>/<repo>.git)'
    if ([string]::IsNullOrWhiteSpace($url)) { throw 'URL репозитория не введён.' }
    & $git remote add origin $url
}

# 3. Пуш ветки и всех тегов версий.
& $git push -u origin main --tags
