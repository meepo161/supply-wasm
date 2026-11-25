# UI/UX Pro Max Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Улучшить главную и навигационный каркас ERP по рекомендациям UI/UX Pro Max, сохранив текущую бизнес-логику и незакоммиченную работу пользователя.

**Architecture:** Изменения ограничиваются презентационным слоем Compose и чистыми функциями форматирования. Существующие ViewModel, API, DTO и права не меняются; визуальные решения используют текущие семантические токены `ErpTheme`.

**Tech Stack:** Kotlin 2, Compose Multiplatform, Material 3, Kotlin/Wasm и JS, `kotlin.test`, Gradle Wrapper.

---

## Проверенные рекомендации skill

Команда `python .../search.py "industrial ERP dashboard desktop" --design-system --variance 2 --motion 1 --density 8` вернула **Minimalism & Swiss Style**, высокую плотность, спокойные hover 200–250 мс, контраст не ниже 4.5:1 и видимый фокус. Это соответствует `DESIGN.md`; рекомендованные landing-pattern, GSAP, новые шрифты и замена фирменного синего не применяются к desktop ERP.

Запрос `keyboard focus navigation --domain ux` подтвердил необходимость полного клавиатурного управления, логичного порядка фокуса и видимого индикатора. Запрос `responsive dense dashboard --domain ux` подтвердил недопустимость горизонтального переполнения и необходимость карточного представления на узком экране.

### Task 1: Изоляция и чистая база

**Files:**
- Create: `.claude/worktrees/ui-ux-pro-max/`
- Create: `docs/plans/2026-11-25-ui-ux-pro-max-design.md`

**Step 1:** Создать worktree и ветку от `develop`.

```bash
git worktree add .claude/worktrees/ui-ux-pro-max -b feature/ui-ux-pro-max develop
```

**Step 2:** Проверить чистую базу.

```bash
./gradlew.bat --no-daemon :app:shared:wasmJsTest :app:shared:jsTest
```

Expected: `BUILD SUCCESSFUL`.

**Step 3:** Перенести текущие изменения пользователя без коммита.

```bash
git diff --binary | git -C .claude/worktrees/ui-ux-pro-max apply -
```

Expected: все перенесённые файлы остаются modified, исходная рабочая копия не меняется.

### Task 2: Проверяемая презентационная логика главной

**Files:**
- Create: `app/shared/src/commonMain/kotlin/org/example/project/ui/home/HomePresentation.kt`
- Create: `app/shared/src/commonTest/kotlin/org/example/project/HomePresentationTest.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/home/HomeScreen.kt`

**Step 1: Написать failing test**

```kotlin
package org.example.project

import org.example.project.ui.home.HomeActionQueueItem
import org.example.project.ui.home.actionQueueContentDescription
import org.example.project.ui.home.homeTaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class HomePresentationTest {
    @Test
    fun overdueSummaryHasPriorityAndKeepsRealCounts() {
        assertEquals(
            "просрочено 2, открыто 7, на приёмке 3",
            homeTaskSummary(false, 5, 7, 2, 3)
        )
    }

    @Test
    fun personalEmptySummaryCanShowCompanyOverdue() {
        assertEquals(
            "у вас просрочек нет, по компании просрочено 4",
            homeTaskSummary(false, 4, 0, 0, 0)
        )
    }

    @Test
    fun actionQueueItemHasCompleteAccessibleName() {
        val item = HomeActionQueueItem("Приёмки", "Укажите ячейки", 2, "WMS")
        assertEquals("Приёмки. Укажите ячейки. Количество: 2.", actionQueueContentDescription(item))
    }
}
```

**Step 2: Запустить тест и подтвердить красный результат**

```bash
./gradlew.bat --no-daemon :app:shared:wasmJsBrowserTest --tests "org.example.project.HomePresentationTest"
```

Expected: компиляция падает, потому что функции ещё не определены.

**Step 3: Добавить минимальную реализацию**

```kotlin
package org.example.project.ui.home

internal fun homeTaskSummary(
    companyScope: Boolean,
    companyOverdueCount: Int,
    openCount: Int,
    overdueCount: Int,
    reviewCount: Int
): String = when {
    overdueCount > 0 -> "просрочено $overdueCount, открыто $openCount, на приёмке $reviewCount"
    openCount > 0 -> "открыто $openCount, на приёмке $reviewCount, просрочек нет"
    companyOverdueCount > 0 && !companyScope ->
        "у вас просрочек нет, по компании просрочено $companyOverdueCount"
    companyScope -> "по компании просроченных задач нет"
    else -> "просроченных задач нет"
}

internal fun actionQueueContentDescription(item: HomeActionQueueItem): String =
    "${item.title}. ${item.subtitle}. Количество: ${item.count}."
```

`HeroCard` использует `homeTaskSummary(...)` вместо локального `when`, а карточка очереди использует доступное имя из `actionQueueContentDescription`.

**Step 4: Запустить тест и подтвердить зелёный результат**

Run: команда из Step 2.  
Expected: `BUILD SUCCESSFUL`, три новых теста проходят.

**Step 5: Сохранность исходных изменений**

Оставить производственный код и тесты незакоммиченными вместе с исходными изменениями пользователя: новый helper зависит от `HomeActionQueueItem`, поэтому изолированный коммит только новых файлов был бы нерабочим.

### Task 3: Очередь действий — плотность, адаптивность и клавиатура

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/home/HomeScreen.kt:325`

**Step 1:** Добавить `MutableInteractionSource`, `collectIsHoveredAsState`, `erpInteractive`, `Role.Button` и семантику доступного имени каждой карточке.

```kotlin
val interaction = remember { MutableInteractionSource() }
val hovered by interaction.collectIsHoveredAsState()
Row(
    modifier = itemModifier
        .clip(shape)
        .background(if (hovered) scheme.primaryContainer.copy(alpha = 0.45f) else scheme.surfaceContainerLow)
        .border(1.dp, if (hovered) scheme.primary else scheme.outlineVariant, shape)
        .erpInteractive(interaction, shape)
        .clickable(interactionSource = interaction, indication = null) { onNavigate(item.destination) }
        .semantics {
            role = Role.Button
            contentDescription = actionQueueContentDescription(item)
        }
        .padding(horizontal = 12.dp, vertical = 10.dp)
)
```

**Step 2:** Через `BoxWithConstraints` на ширине менее 640 dp растягивать элементы на всю строку; на широком экране использовать `widthIn(min = 260.dp, max = 420.dp)`. Текстовой колонке дать `weight(1f)`, чтобы длинные подписи переносились без горизонтального переполнения.

**Step 3:** Убрать ad-hoc `Brand.Blue.copy(...)` из фона и рамки; цвет счётчика и выбранного состояния должен исходить из `MaterialTheme.colorScheme`.

**Step 4:** Запустить тесты.

```bash
./gradlew.bat --no-daemon :app:shared:wasmJsTest :app:shared:jsTest
```

Expected: `BUILD SUCCESSFUL`.

**Step 5:** Оставить изменения поверх пользовательского файла незакоммиченными; не смешивать их в коммит без запроса.

### Task 4: Навигационный каркас и векторная иконка избранного

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/theme/Components.kt:588`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/main/MainScaffold.kt:601,887,1071,1213`

**Step 1:** Добавить `ErpIconGlyph.Star` и векторный контур звезды в существующий `DrawScope.drawGlyph`, без ресурсов и зависимостей.

**Step 2:** Заменить текстовые `★`/`☆` в `TopBar` на `ErpIcon`. Добавить interaction source, `erpInteractive`, `Role.Button` и доступное имя «Добавить раздел в избранное»/«Убрать раздел из избранного».

**Step 3:** Добавить роли и состояние `selected` пунктам боковой и нижней навигации. Сохранить существующие цвета, размеры, маршруты и фильтрацию по правам.

**Step 4:** Для пользовательского меню использовать тот же клавиатурный фокус и `Role.Button`; не менять обработчики входа в профиль и выхода.

**Step 5:** Запустить общие Wasm/JS-тесты; ожидается `BUILD SUCCESSFUL`.

**Step 6:** При необходимости закоммитить только `Components.kt`, не включая `MainScaffold.kt` с перенесёнными пользовательскими изменениями.

### Task 5: Браузерная приёмка и документация

**Files:**
- Modify: `DESIGN.md`
- Modify: `NEED_UPDATE.md` — только фактически подтверждённые пункты
- Modify: `docs/plans/2026-11-25-ui-ux-pro-max-design.md` — фактический результат

**Step 1:** Запустить изменённый Wasm-клиент на `8081` при работающем backend `8080`.

```bash
PORT=8081 ./gradlew.bat --no-daemon :app:webApp:wasmJsBrowserDevelopmentRun
```

**Step 2:** Войти реальным пользователем и проверить главную: очередь открывает разрешённые разделы, счётчики соответствуют данным, помощник и уведомления продолжают работать.

**Step 3:** Проверить 1440, 768 и 375 px, светлую и тёмную темы. Ожидается отсутствие горизонтального переполнения, обрезанных действий и нечитаемых состояний.

**Step 4:** Проверить Tab/Shift+Tab/Enter для очереди, избранного, меню пользователя и навигации. Фокус виден и не скрыт фиксированными панелями.

**Step 5:** Проверить WMS и другие пункты `NEED_UPDATE.md`, затронутые общим каркасом. Не отмечать исправленным то, что не воспроизведено или не проверено.

**Step 6:** Финальный прогон.

```bash
./gradlew.bat --no-daemon :app:shared:wasmJsTest :app:shared:jsTest
git status --short
```

Expected: `BUILD SUCCESSFUL`; build-артефакты не появляются в Git; исходная рабочая копия вне worktree не изменена этой задачей.

**Step 7:** Зафиксировать фактический результат, непроверенные сценарии и оставшиеся риски. Не выполнять merge или push без отдельного запроса.
