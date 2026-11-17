# История версий и GitHub

Готовые jar старых версий лежат в `versions/`:

| Версия | Файл | Что было |
|---|---|---|
| 0.2.6 | `versions/overtone-party-0.2.6.jar` | Поиск локальных треков в площадке каждого слушателя |
| 0.2.7 | `versions/overtone-party-0.2.7.jar` | Замена любого недоступного трека, 9 с таймаут, SoundCloud как резерв |
| 0.3.0 | `versions/overtone-party-0.3.0.jar` | Несколько независимых вечеринок, обзор, открытые/приватные группы |

Версии 0.2.0–0.2.5 отдельными файлами не сохранились — их изменения описаны в `README.md`.

## Как залить проект на GitHub со всеми версиями

Портативный Git уже установлен:
`C:\Users\Александр\AppData\Local\Programs\PortableGit\cmd\git.exe`.
GitHub CLI: `C:\Users\Александр\AppData\Local\Programs\GitHubCLI\bin\gh.exe`.

Локальная история уже создана: `v0.2.6` → `v0.2.7` → `v0.3.0` (ветка `main`, рабочее дерево чистое).
Осталось только авторизоваться в GitHub и запушить.

Самый простой способ — запустить из папки проекта:

```powershell
powershell -ExecutionPolicy Bypass -File .\push-to-github.ps1
```

Скрипт сам выполнит вход через браузер, спросит URL репозитория и запушит `main` со всеми тегами.

Вручную:

```powershell
& 'C:\Users\Александр\AppData\Local\Programs\GitHubCLI\bin\gh.exe' auth login --web
$git = 'C:\Users\Александр\AppData\Local\Programs\PortableGit\cmd\git.exe'
& $git remote add origin https://github.com/<вас>/overtone-party.git
& $git push -u origin main --tags
```

Для будущих версий: после каждого релиза копируйте готовый jar в `versions/`,
делайте `git add .`, `git commit -m "Release X.Y.Z"`, `git tag vX.Y.Z`, `git push --tags`.
