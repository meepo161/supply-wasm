# Разработка и проверка

## Перед запуском

Проект собирается Gradle Wrapper (`gradlew.bat` в Windows, `./gradlew` в Unix). Версия Gradle закреплена в `gradle/wrapper/gradle-wrapper.properties` — 9.5.1. Для локальной сборки требуется установленный JDK; точная требуемая версия JDK в репозитории не закреплена. При первом запуске Wrapper скачает Gradle и зависимости.

Команды ниже запускаются из корня проекта. В Windows замените `./gradlew` на `./gradlew.bat`.

## Запуск

| Компонент | Команда | Результат |
| --- | --- | --- |
| Сервер | `./gradlew :server:run` | Запускает Ktor/Netty на `0.0.0.0:8080`; маршрут `GET /` отвечает `Hello, Ktor!`. |
| Веб-приложение, Wasm | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` | Запускает разработческий сервер для браузера с Kotlin/Wasm. |
| Веб-приложение, JS | `./gradlew :app:webApp:jsBrowserDevelopmentRun` | Запускает JS-вариант веб-приложения. |

Адрес разработческого сервера веб-приложения берите из вывода Gradle: он не задан в исходном коде проекта.

## Тесты

| Область | Команда |
| --- | --- |
| Сервер | `./gradlew :server:test` |
| Общий код веб-приложения, Wasm | `./gradlew :app:shared:wasmJsTest` |
| Общий код веб-приложения, JS | `./gradlew :app:shared:jsTest` |

Серверный тест проверяет код ответа и текст маршрута `GET /`. Тест в `app/shared/src/webTest` пока проверяет только пример `1 + 2 = 3` и не покрывает поведение интерфейса. В `core` объявлена зависимость для тестов, но тестовых исходников сейчас нет.

## Где менять код

- `app/shared/src/commonMain` — общий интерфейс Compose и логика веб-приложения.
- `app/shared/src/jsMain` и `app/shared/src/wasmJsMain` — реализации платформенных функций.
- `app/webApp/src/webMain` — точка входа браузерного приложения и ресурсы страницы.
- `core/src/commonMain` — общая функция `sayHello`, используемая веб-приложением и сервером.
- `server/src/main` — приложение Ktor и маршрут.

Версии Kotlin, Compose, Ktor и остальных библиотек находятся в `gradle/libs.versions.toml`.
