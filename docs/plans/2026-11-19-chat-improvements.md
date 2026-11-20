# Chat Experience Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Довести единый Chat, Task Chat, файлы и уведомления до согласованного в `2026-11-19-chat-improvements-design.md` рабочего состояния без смешивания прав разных видов чатов.

**Architecture:** Расширить существующие Conversations API и Task Chat API отдельно, добавив совместимые DTO и общий UI-паттерн. Серверный агрегирующий list API объединяет авторизованные проекции; realtime остаётся per-kind с курсорным догоном. Push является отдельным отключаемым адаптером, центр уведомлений остаётся основным каналом.

**Tech Stack:** Kotlin 2.4.21, Ktor 3.6.0, PostgreSQL 16, Flyway, jOOQ, Koin, Kotlin/Wasm/JS, Compose Multiplatform 1.12.2, kotlinx.serialization, существующий browser/Wasm toolchain.

---

## Общие правила исполнения

- Использовать TDD: сначала тест и наблюдаемый FAIL, затем минимальная реализация и PASS.
- Не менять существующие миграции V30/V31; новая схема — V33 и далее.
- Не запускать серверные наборы параллельно: все используют `erp_test` на `localhost:5433`.
- Команды Windows запускать из корня через `./gradlew.bat --no-daemon ...`.
- Не делать commit/push без отдельного явного поручения пользователя. Вместо commit-чекпойнтов фиксировать пройденные тесты и `git status`.
- После каждого пакета обновлять фактические результаты, не отмечая непроверенное как DONE.

### Task 1: Схема и общие DTO расширенного чата

**Files:**
- Create: `server/src/main/resources/db/migration/V33__chat_experience.sql`
- Modify: `core/src/commonMain/kotlin/org/example/project/dto/ConversationDtos.kt`
- Modify: `core/src/commonMain/kotlin/org/example/project/dto/ChatDtos.kt`
- Modify: `core/src/commonMain/kotlin/org/example/project/dto/TaskChatWorkspaceDtos.kt`
- Test: `server/src/test/kotlin/org/example/project/ChatExperienceSchemaTest.kt`

**Step 1:** Написать schema-тест, который применяет миграции в `erp_test` и проверяет новые колонки/таблицы и ограничения. Проверить как минимум `conversation_message_revisions`, `conversation_reactions`, `conversation_mentions`, `conversation_read_states`, `conversation_dialog_states`, `task_chat_message_revisions`, `chat_push_subscriptions` и edit/revoke/forward/reply поля сообщений.

**Step 2:** Запустить `./gradlew.bat --no-daemon :server:test --tests org.example.project.ChatExperienceSchemaTest`; ожидается FAIL до V33.

**Step 3:** Добавить V33 одной миграцией. Использовать составные PK как в V31, FK с `ON DELETE CASCADE`, монотонные read cursor, индексы по `(user_id, conversation_id)` и `(message_id, mentioned_user_id)`. Добавить `edited_at`, `revoked_at`, `reply_to_id`, `source_kind`, `source_message_id`, `source_author_id`, `source_created_at`; не удалять старые колонки.

**Step 4:** Расширить DTO обратносовместимыми default-полями:

```kotlin
data class ConversationMessageDto(
    val id: Long,
    val conversationId: Long,
    val author: ChatAuthorDto? = null,
    val content: String,
    val createdAt: String,
    val attachments: List<AttachmentDto> = emptyList(),
    val replyTo: ReplyPreviewDto? = null,
    val reactions: List<ReactionSummaryDto> = emptyList(),
    val mentionedUserIds: List<Long> = emptyList(),
    val readByCount: Int = 0,
    val editedAt: String? = null,
    val revokedAt: String? = null,
    val source: MessageSourceDto? = null
)
```

Добавить `EditMessageRequest(content, version?)`, `ForwardMessageRequest(targetKind, targetId, idempotencyKey)`, `ConversationListPageDto(items, nextCursor, hasMore)`, `ConversationListItemDto` и `MessageSourceDto`. В `ChatMessageDto` добавить те же lifecycle/source поля с default `null`.

**Step 5:** Повторить schema-тест и `./gradlew.bat --no-daemon :server:compileKotlin :app:shared:compileKotlinWasmJs`; ожидается PASS/BUILD SUCCESSFUL.

### Task 2: Расширенные сообщения DIRECT/GROUP/PROJECT

**Files:**
- Modify: `server/src/main/kotlin/org/example/project/repository/ConversationRepository.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/ConversationService.kt`
- Modify: `server/src/main/kotlin/org/example/project/route/ConversationRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/realtime/ConversationEventBus.kt`
- Modify: `server/src/main/kotlin/org/example/project/realtime/ConversationWebSocketRoutes.kt`
- Test: `server/src/test/kotlin/org/example/project/ConversationExperienceRoutesTest.kt`
- Test: `server/src/test/kotlin/org/example/project/ConversationWebSocketTest.kt`

**Step 1:** Добавить failing API-тесты: reply в том же conversation, reaction toggle, mention notification, read monotonicity, search без утечки, edit только автором, revision сохраняется, revoke оставляет tombstone, SYSTEM не применим, forward требует read source/write target, закрытый/чужой conversation запрещён.

**Step 2:** Запустить новый тест и зафиксировать FAIL по отсутствующим endpoint/полям.

**Step 3:** Реализовать repository-запросы и service-команды. Каждое изменение выполнять в транзакции, проверять актуальное membership/project access на сервере. Edit сохраняет прежний текст в revisions; revoke не удаляет запись/аудит; search использует `ILIKE` с экранированием и cursor/limit.

**Step 4:** Добавить endpoints:

```text
POST /api/conversations/{id}/messages/{messageId}/reactions
POST /api/conversations/{id}/messages/{messageId}/edit
POST /api/conversations/{id}/messages/{messageId}/revoke
POST /api/conversations/{id}/messages/{messageId}/forward
POST /api/conversations/{id}/read
GET  /api/conversations/{id}/messages/search
```

**Step 5:** Расширить realtime событиями `MESSAGE`, `EDITED`, `REVOKED`, `REACTION`, `READ`, `TYPING`; публиковать после commit и проверять доступ при подписке/отправке события.

**Step 6:** Запустить `:server:test --tests ...ConversationExperienceRoutesTest --tests ...ConversationWebSocketTest`; ожидается PASS.

### Task 3: Edit/revoke/forward и lifecycle Task Chat

**Files:**
- Modify: `server/src/main/kotlin/org/example/project/repository/ChatRepository.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/ChatService.kt`
- Modify: `server/src/main/kotlin/org/example/project/route/ChatRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/realtime/ChatEventBus.kt`
- Modify: `server/src/main/kotlin/org/example/project/realtime/ChatWebSocketRoutes.kt`
- Test: `server/src/test/kotlin/org/example/project/ChatWorkspaceRoutesTest.kt`
- Test: `server/src/test/kotlin/org/example/project/ChatWorkspaceWebSocketTest.kt`

**Step 1:** Добавить failing тесты edit/revoke/forward, истории редакций, запрета SYSTEM, read-only CLOSED/CANCELLED и отзыва доступа.

**Step 2:** Запустить выбранные тесты, убедиться в FAIL.

**Step 3:** Реализовать команды в существующем `ChatService`, используя `canRead`, `canWrite`, `isTaskClosed`; read marks остаются разрешены после закрытия. Добавить publish после commit и курсорный догон lifecycle-событий.

**Step 4:** Запустить `ChatWorkspaceRoutesTest`, `ChatWorkspaceWebSocketTest`, `ChatRoutesTest`, `ChatWebSocketTest`; ожидается PASS.

### Task 4: Несколько файлов и защита команд

**Files:**
- Modify: `server/src/main/kotlin/org/example/project/route/ConversationRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/route/ChatRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/AttachmentService.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/ConversationService.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/ChatService.kt`
- Create: `server/src/main/kotlin/org/example/project/service/MessageRateLimiter.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/MessageMultipart.kt`
- Test: `server/src/test/kotlin/org/example/project/AttachmentMessageRoutesTest.kt`
- Test: `server/src/test/kotlin/org/example/project/MessageRateLimiterTest.kt`

**Step 1:** Добавить failing тесты: два/три файла в одном сообщении, повтор multipart с одним ключом без дублей, превышение количества/суммарного размера, очистка prepared files после rollback, rate limit и отсутствие блокировки других пользователей.

**Step 2:** Запустить тесты и зафиксировать FAIL.

**Step 3:** Принимать повторяющиеся multipart parts `files` (сохранить совместимость с `file`), ограничивать число и суммарный размер до сохранения, сохранять attachments атомарно с сообщением. При любой ошибке удалять все prepared files. `messageFormData` принимает `files: List<AttachmentFileData>` и повторяет part `files`.

**Step 4:** Добавить небольшой per-user/per-chat in-memory rate limiter без новой бизнес-зависимости; ограничение проверяется до записи и не затрагивает чтение/reconnect.

**Step 5:** Запустить attachment/rate tests и все Chat/Conversation route tests; ожидается PASS.

### Task 5: Серверный единый список, pin/mute/archive и поиск

**Files:**
- Create: `server/src/main/kotlin/org/example/project/service/UnifiedChatListService.kt`
- Create: `server/src/main/kotlin/org/example/project/route/UnifiedChatRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/repository/ConversationRepository.kt`
- Modify: `server/src/main/kotlin/org/example/project/repository/ChatRepository.kt`
- Modify: `server/src/main/kotlin/org/example/project/plugins/Routing.kt`
- Modify: `server/src/main/kotlin/org/example/project/di/AppModule.kt`
- Create: `server/src/test/kotlin/org/example/project/UnifiedChatRoutesTest.kt`

**Step 1:** Написать failing тесты списка: четыре kind в одной выдаче, stable `(kind,id)`, preview/unread/mentions, pin-first/activity ordering, query/filter, cursor без дублей, mute/archive и исчезновение записи после отзыва доступа.

**Step 2:** Запустить тест и зафиксировать FAIL.

**Step 3:** Реализовать авторизованные проекции для conversations и task chats, затем детерминированное слияние с cursor на основе `(lastMessageAt, kind, id)`. Для выборки страницы использовать ограничения на источники, не полные списки задач/проектов. Права применять внутри каждого repository-запроса.

**Step 4:** Добавить `GET /api/chat/workspace`, `POST /api/chat/workspace/{kind}/{id}/state` для pin/mute/archive и подключить Koin/Routing.

**Step 5:** Запустить `UnifiedChatRoutesTest` и полный `:server:test`; ожидается PASS.

### Task 6: Клиентские API, per-chat состояние и realtime merge

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/ConversationApiClient.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/ConversationRepository.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/ConversationRealtime.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/ChatApiClient.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/data/ChatRealtime.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationViewModel.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationUiExtensions.kt`
- Test: `app/shared/src/commonTest/kotlin/org/example/project/ConversationUnifiedListTest.kt`
- Test: `app/shared/src/commonTest/kotlin/org/example/project/ConversationRealtimeTest.kt`
- Create: `app/shared/src/commonTest/kotlin/org/example/project/ConversationDraftsTest.kt`

**Step 1:** Добавить failing common-тесты на per-chat draft/pending files/scroll, stale response discard, reconnect dedupe, lifecycle events и сохранение списка при ошибке refresh.

**Step 2:** Запустить `./gradlew.bat --no-daemon :app:shared:wasmJsTest --tests ...`; ожидается FAIL.

**Step 3:** Добавить методы API и типизированный parsing событий. Хранить `drafts`, `pendingFiles`, `scrollPositions` по `conversationKey(kind,id)`, не сбрасывать их в `selectConversation`. Повтор использует прежний idempotency key только для той же незавершённой команды.

**Step 4:** Запустить Wasm и JS common tests; ожидается BUILD SUCCESSFUL.

### Task 7: UI единого списка, создания и адаптивной навигации

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationsScreen.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationCreateDialog.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationViewModel.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationUiExtensions.kt`
- Test: `app/shared/src/commonTest/kotlin/org/example/project/ConversationUiLogicTest.kt`

**Step 1:** Добавить failing тесты filter/sort/unread labels и workspace navigation; зафиксировать FAIL.

**Step 2:** Реализовать search field, фильтры, preview/time/badges, pin/mute/archive actions, loading-more cursor, различие пустого списка и пустого фильтра. Использовать существующие `ErpIcon`, `PillBadge`, `EmptyState`, theme; новых UI-зависимостей не добавлять.

**Step 3:** В создании чата добавить поиск сотрудников, selected chips/список, loading/disable и закрытие dialog только после успешного создания. Сохранить введённые значения при ошибке.

**Step 4:** Исправить narrow Task Chat: явный Back к единому списку, `showChatList=false` внутри выбранного TASK; desktop список не исчезает.

**Step 5:** Запустить common tests, Wasm/JS tests и браузерный сценарий на 8081; ожидается PASS и реальная проверка list → TASK → back.

### Task 8: Общая лента сообщений, composer и Task Chat lifecycle UI

**Files:**
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationPanel.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/conversations/ConversationsScreen.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/tasks/TaskChatWorkspace.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/tasks/TaskChatLogic.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/tasks/TaskViewModel.kt`
- Test: `app/shared/src/commonTest/kotlin/org/example/project/TaskChatLogicTest.kt`
- Test: `app/shared/src/commonTest/kotlin/org/example/project/ConversationUiLogicTest.kt`

**Step 1:** Добавить failing тесты autoscroll только у низа, сохранения anchor при prepend, own-message alignment, edit/revoke visibility и closed-task restrictions.

**Step 2:** Реализовать общие компоненты сообщения/reaction/composer без переноса TASK в Conversations API. Добавить reply, reaction picker, mention suggestions, formatting, search/highlight, edit/revoke/forward, read/delivery и date/unread separators.

**Step 3:** Добавить multi-file pending list, image preview только для разрешённых image MIME, прогресс/ошибку/retry, Ctrl+Enter/Ctrl+F/Escape. При ошибке draft/files остаются.

**Step 4:** Для Task Chat скрыть все изменяющие действия в CLOSED/CANCELLED, сохранить ERP context и read marks.

**Step 5:** Запустить все shared Wasm/JS tests и реальные browser scenarios; ожидается PASS.

### Task 9: Push subscriptions и Service Worker

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `server/build.gradle.kts`
- Modify: `server/src/main/kotlin/org/example/project/config/AppConfig.kt`
- Create: `server/src/main/kotlin/org/example/project/service/PushService.kt`
- Create: `server/src/main/kotlin/org/example/project/route/PushRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/NotificationService.kt`
- Modify: `server/src/main/kotlin/org/example/project/plugins/Routing.kt`
- Modify: `server/src/main/kotlin/org/example/project/di/AppModule.kt`
- Create: `app/webApp/src/webMain/resources/service-worker.js`
- Modify: `app/webApp/src/webMain/resources/index.html`
- Create: `app/webApp/src/webMain/resources/push-client.js`
- Create: `server/src/test/kotlin/org/example/project/PushRoutesTest.kt`

**Step 1:** Написать failing тесты neutral payload, owner-only subscribe/unsubscribe, удаление недействительной subscription, запрет утечки title/content и отключённый режим без VAPID.

**Step 2:** Запустить `PushRoutesTest`; ожидается FAIL.

**Step 3:** Проверить совместимость Web Push библиотеки с JDK/Ktor перед добавлением. Использовать версионную dependency только для стандартного VAPID/aes128gcm; не реализовывать криптографию вручную. Конфигурация читается из переменных среды, секреты не записываются в репозиторий.

**Step 4:** Реализовать `GET /api/push/public-key`, `POST /api/push/subscriptions`, `DELETE /api/push/subscriptions`; отправка инициируется сохранённым notification, payload содержит только neutral category/event id. Ошибка push не откатывает сообщение/notification.

**Step 5:** Service Worker обрабатывает `push`, `notificationclick` и навигацию через существующую страницу; JS регистрирует SW только при поддержке и разрешении. Denied/unsupported сохраняет центр как рабочий fallback.

**Step 6:** Запустить `PushRoutesTest`, полный server test и Wasm build. Реальную доставку при закрытой вкладке проверить только при настроенных VAPID keys; иначе явно записать инфраструктурное ограничение.

### Task 10: Настройки уведомлений, документация и сквозная приёмка

**Files:**
- Modify: `server/src/main/kotlin/org/example/project/route/NotificationRoutes.kt`
- Modify: `server/src/main/kotlin/org/example/project/service/NotificationService.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/notifications/NotificationScreen.kt`
- Modify: `app/shared/src/commonMain/kotlin/org/example/project/ui/notifications/NotificationViewModel.kt`
- Modify: `docs/decisions.md`
- Modify: `docs/features.md`
- Modify: `docs/implementation-plan.md`
- Modify: `PROGRESS.md`
- Modify: `TASKS.md`
- Test: `server/src/test/kotlin/org/example/project/NotificationRoutesTest.kt`

**Step 1:** Добавить failing тесты per-chat mute, отдельного mention preference, группировки повторных событий и сохранения обязательных событий.

**Step 2:** Реализовать настройки и UI с учётом серверных прав; не показывать фиктивные переключатели для неподдержанных категорий.

**Step 3:** Запустить полный набор последовательно:

```bash
./gradlew.bat --no-daemon :server:test
./gradlew.bat --no-daemon :app:shared:wasmJsTest
./gradlew.bat --no-daemon :app:shared:jsTest
```

Ожидается BUILD SUCCESSFUL для каждой команды.

**Step 4:** Поднять PostgreSQL, backend 8080 и frontend 8081 стандартными командами. Выполнить HTTP/browser приёмку двумя пользователями: DIRECT/GROUP/PROJECT/TASK, reconnect, multi-file, edit/revoke/forward, поиск, pin/mute/archive, mobile back, закрытая задача, отзыв прав, denied push и neutral notification click.

**Step 5:** Обновить документы фактическими результатами и ограничениями. Проверить `git status`; удалить только созданные проверками ненужные артефакты. Не отмечать внешний push/браузерную приёмку DONE без фактического запуска.
