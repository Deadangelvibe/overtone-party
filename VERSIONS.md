# История версий и GitHub

Готовые jar старых версий лежат в `versions/`:

| Версия | Файл | Что было |
|---|---|---|
| 0.2.6 | `versions/overtone-party-0.2.6.jar` | Поиск локальных треков в площадке каждого слушателя |
| 0.2.7 | `versions/overtone-party-0.2.7.jar` | Замена любого недоступного трека, 9 с таймаут, SoundCloud как резерв |
| 0.3.0 | `versions/overtone-party-0.3.0.jar` | Несколько независимых вечеринок, обзор, открытые/приватные группы |

Версии 0.2.0–0.2.5 отдельными файлами не сохранились — их изменения описаны в `README.md`.

## Как залить проект на GitHub со всеми версиями

Git в системе не установлен. После установки [Git for Windows](https://git-scm.com/download/win)
выполните в папке проекта (`C:\Users\Александр\overtone-party`):

```powershell
git init
git config user.name "Ваше имя"
git config user.email "you@example.com"

# История по версиям: сначала старые артефакты, потом исходники 0.3.0
git add versions/overtone-party-0.2.6.jar README.md
git commit -m "Release 0.2.6"
git tag v0.2.6

git add versions/overtone-party-0.2.7.jar
git commit -m "Release 0.2.7"
git tag v0.2.7

git add .
git commit -m "Release 0.3.0: multiple parties, browser, private parties"
git tag v0.3.0

# Далее — создайте репозиторий на GitHub и выполните команды ниже (замените URL)
git remote add origin https://github.com/<вас>/overtone-party.git
git branch -M main
git push -u origin main --tags
```

Для будущих версий: после каждого релиза копируйте готовый jar в `versions/`,
делайте `git add .`, `git commit -m "Release X.Y.Z"`, `git tag vX.Y.Z`, `git push --tags`.
