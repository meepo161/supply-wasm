# Разработка и проверка

## Адреса и порты — обязательно

- **Backend / API:** `http://localhost:8080`
- **Frontend / WASM-клиент:** `http://localhost:8081`
- **API из браузерного клиента:** только `http://localhost:8080/api/...`
- **Страница входа для пользователя:** `http://localhost:8081`

Не меняй порты местами. Не направляй клиент на `localhost:8081/api/...`: это вызывает запрос frontend к самому себе и приводит к 404. Backend сначала запускается на `8080`, frontend затем запускается на `8081`.

## Перед запуском

Проект собирается Gradle Wrapper (`gradlew.bat` в Windows, `./gradlew` в Unix). Версия Gradle закреплена в `gradle/wrapper/gradle-wrapper.properties` — 9.5.1. Для локальной сборки требуется установленный JDK; точная требуемая версия JDK в репозитории не закреплена. При первом запуске Wrapper скачает Gradle и зависимости.

Команды ниже запускаются из корня проекта. В Windows замените `./gradlew` на `./gradlew.bat`.

## Запуск

| Компонент | Команда | Адрес |
| --- | --- | --- |
| Сервер | `PORT=8080 ./gradlew.bat --no-daemon :server:run` | `http://localhost:8080` |
| Веб-приложение, Wasm | `PORT=8081 ./gradlew.bat --no-daemon :app:webApp:wasmJsBrowserDevelopmentRun` | `http://localhost:8081` |

Сначала запускается backend, затем frontend. Порт frontend задаётся через `PORT`; адрес также выводится в журнале запуска.

### IntelliJ IDEA

В `.idea/runConfigurations` сохранены постоянные конфигурации:

- `ERP PostgreSQL` — поднимает контейнер через Docker Compose и ждёт готовности PostgreSQL; используется Git Bash `C:/Program Files/Git/bin/bash.exe`;
- `ERP Backend 8080` — Gradle `:server:run` с `PORT=8080`;
- `ERP Web Wasm 8081 Continuous` — Gradle `:app:webApp:wasmJsBrowserDevelopmentRun` с `PORT=8081` и `--continuous`;
- `ERP Start All` — Compound-запуск всех трёх компонентов.

Для ручного пошагового запуска сначала выполни `ERP PostgreSQL`, затем backend и frontend. Не используй старые временные конфигурации без явного `PORT`.

## Тесты

| Область | Команда |
| --- | --- |
| Сервер | `./gradlew.bat --no-daemon :server:test` |
| Smoke-набор (≈2,5 мин) | `./gradlew.bat --no-daemon :server:test --tests "org.example.project.smoke.*"` |
| Общий код веб-приложения, Wasm | `./gradlew.bat :app:shared:wasmJsTest` |
| Общий код веб-приложения, JS | `./gradlew.bat :app:shared:jsTest` |

Серверные тесты используют отдельную базу `erp_test` на `localhost:5433`; другой порт задаётся переменной окружения `ERP_TEST_DB_PORT`, имя базы — `ERP_TEST_DB_NAME` (например, `erp_test_invoice` для работы в отдельном worktree, чтобы параллельные прогоны не пересоздавали общую `erp_test`) (например, `5434`, если 5433 занят другим PostgreSQL); не запускай их параллельно с другим набором тестов на этой базе. Проверяются реальные API и PostgreSQL-миграции. Smoke-набор (`server/src/test/kotlin/org/example/project/smoke`) обходит все маршруты Ktor и реальные сквозные сценарии; отчёт пишется в `server/build/smoke-reports/`, подтверждённые дефекты описаны в `docs/audit/`. Перед сдачей изменения также проверяется соответствующий пользовательский сценарий через HTTP и браузер, а не только компиляция.

## Таблица приложения

Для рабочих таблиц используется `Un7KCMPDataGrid` из нашего KMP-модуля `app/un7datagrid` (D-45, D-54). Исходники хранятся в репозитории, отдельный Git submodule не используется; в `settings.gradle.kts` подключён `include(":app:un7datagrid")`, а `app/shared` зависит от `project(":app:un7datagrid")`. Токен GitHub Packages не нужен. Сохранение ширин между перезагрузками делается в `app/shared/src/commonMain/kotlin/org/example/project/ui/tables/PersistentDataGrid.kt`; новые таблицы подключай через него, а не напрямую к `Un7KCMPDataGrid`. Происхождение кода и правки D-53 описаны в `app/un7datagrid/README.md`.

## Где менять код

- `app/shared/src/commonMain` — общий интерфейс Compose и логика веб-приложения.
- `app/shared/src/jsMain` и `app/shared/src/wasmJsMain` — реализации платформенных функций.
- `app/webApp/src/webMain` — точка входа браузерного приложения и ресурсы страницы.
- `core/src/commonMain` — общие DTO и контракты API.
- `server/src/main` — Ktor, бизнес-операции, PostgreSQL/jOOQ и миграции.

Версии Kotlin, Compose, Ktor и остальных библиотек находятся в `gradle/libs.versions.toml`.

## Локальный запуск

### PostgreSQL

Для разработки и тестирования используется контейнер PostgreSQL 16 с данными на хосте.

| Действие | Команда | Примечание |
| --- | --- | --- |
| Поднять базу | `docker compose up -d postgres` | Порт `5433` на хосте проброшен на `5432` в контейнере, чтобы не конфликтовать с локальным PostgreSQL. |
| Проверить подключение | `docker compose exec postgres pg_isready -U erp -d erp` | Или через JDBC: `jdbc:postgresql://localhost:5433/erp`. |
| Остановить базу | `docker compose stop postgres` | Данные сохраняются в `./.local/postgres/data`. |
| Удалить контейнер | `docker compose rm -f postgres` | Данные на хосте остаются. |
| Полный сброс | `docker compose down -v` | **Удаляет volume и все данные.** Перед сбросом убедитесь, что это допустимо. |

Параметры подключения по умолчанию:

- URL: `jdbc:postgresql://localhost:5433/erp`
- Пользователь: `erp`
- Пароль: `erp`
- База: `erp`

Данные контейнера хранятся в `./.local/postgres/data` и игнорируются Git.

### Миграции и генерация jOOQ

После подъёма PostgreSQL примените миграции и сгенерируйте код jOOQ:

```bash
./gradlew :server:flywayMigrate :server:generateJooq
```

В Windows используйте `./gradlew.bat`.

Задачи используют значения по умолчанию из `server/build.gradle.kts`. При необходимости их можно переопределить через `gradle.properties` или флаги:

- `erp.db.url`
- `erp.db.user`
- `erp.db.password`
- `erp.db.locations`
- `erp.db.baselineOnMigrate`

### Запуск сервера и клиента

Используй команды из раздела «Запуск» выше. `PORT` переопределяет порт сервера или frontend dev-сервера, но для локальной разработки значения зафиксированы: backend — `8080`, frontend — `8081`. Перед перезапуском останови прежний процесс соответствующего компонента, не меняй их адреса местами.
