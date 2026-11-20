# Модуль рабочей таблицы

`Un7KCMPDataGrid` и его вспомогательные компоненты находятся в этом репозитории как обычный KMP-модуль (D-45, D-54). Исходная версия взята из публичного проекта [unchil/ComposeDataGrid](https://github.com/unchil/ComposeDataGrid) `0.2.10-1`; пакет `com.unchil.un7datagrid` сохранён, чтобы не менять импорты в `app/shared`.

Локальные правки:

- `initialColumnWeights` и `onColumnWeightsChange` в `Un7KCMPDataGridConfig` и поддержка восстановления ширин в `Un7KCMPDataGridViewModel` / `Un7KCMPDataGridContent` (D-53, сохранение ширин через `PersistentDataGrid`);
- сборка ограничена целями `js` и `wasmJs`, которые использует веб-клиент; Android/iOS/JVM-цели и публикация в Maven не нужны.

Совместимость с главной сборкой: Kotlin, Compose и зависимости берутся из корневого `gradle/libs.versions.toml`, отдельного Gradle-сборка (composite build) и GitHub Packages больше нет.

В upstream-репозитории на момент переноса не было файла `LICENSE`. До промышленного внедрения нужно проверить условия использования и, при необходимости, заменить компонент или получить разрешение.
