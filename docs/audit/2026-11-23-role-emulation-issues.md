# Реестр недочётов, неудобств и задач по итогам ролевой эмуляции (7 агентов)

**Дата:** 23.11.2026  
**Стенд:** `http://localhost:8091` (Wasm UI) + API `http://localhost:8080` (прокси на `8090`)  
**Состав роя:**
- **Админ (1):** `admin` (`admin`)
- **Проектные менеджеры (2):** `pm-1` (`emu_pm1`), `pm-2` (`emu_pm2`)
- **Снабженцы (2):** `snab-1` (`emu_snab1`), `snab-2` (`emu_snab2`)
- **Кладовщики (2):** `sklad-1` (`emu_sklad1`), `sklad-2` (`emu_sklad2`)

---

## Сводная таблица задач

| ID | Приоритет | Модуль | Краткое описание | Роли |
| --- | --- | --- | --- | --- |
| `ISS-01` | `P1` | Платформа / API | `encodeDefaults = false` в Ktor `ContentNegotiation` скрывает `status` и `version=0` | `pm-1`, `snab-1`, `snab-2`, `sklad-1` |
| `ISS-02` | `P1` | Снабжение | Тупик прав `BUYER`: не может верифицировать поставщика, выбрать offer и утвердить PO | `snab-1`, `snab-2` |
| `ISS-03` | `P1` | Склад / Ожидаемые приёмки | `GET /api/wms/expected-receipts` отдаёт `PENDING_APPROVAL` (падает с `409`) и отрицательный `notAcceptedQuantity` | `sklad-1` |
| `ISS-04` | `P1` | Склад / Права | `KEEPER` получает `403` на `GET /api/purchase-orders` и `/api/suppliers` | `sklad-1` |
| `ISS-05` | `P1` | Проекты | Колонка «Руководитель» в списке проектов всегда показывает `«—»` | `pm-1` |
| `ISS-06` | `P1` | Проекты | `ProjectDetailScreen` жёстко проверяет `canManageMembers = isOwner`, игнорируя `projects:manage_members` | `pm-2` |
| `ISS-07` | `P1` | Проекты / Пользователи | `GET /api/projects/{id}` не возвращает ФИО/логин участников, а `GET /api/users` закрыт `403` для `PM` | `pm-2` |
| `ISS-08` | `P1` | Проекты / Задачи | Нет редактирования (`PUT`/`PATCH`) проектов и задач после создания | `pm-1` |
| `ISS-09` | `P1` | Задачи / Риски | Несогласованный формат `dueDate` (`YYYY-MM-DD` vs ISO-8601) и сырой `ErpTextField` в диалоге риска | `pm-2` |
| `ISS-10` | `P1` | Администрирование | `UserDto` и `UsersGrid` не показывают роли; `EditUserDialog` сбрасывает роли при включении галочки | `admin` |
| `ISS-11` | `P1` | Администрирование | Роли нельзя редактировать или удалять; права принимают произвольные невалидированные строки | `admin` |
| `ISS-12` | `P1` | Администрирование / Аудит | В `AuditTab` нет фильтра по `userId`, пагинации, выпадающих списков и полной локализации действий | `admin` |
| `ISS-13` | `P1` | Склад (WMS) | Склады и ячейки хранения нельзя редактировать, архивировать или удалять | `sklad-1`, `sklad-2` |
| `ISS-14` | `P2` | Проекты | Двухшаговый поток `DRAFT -> open` неудобен в UI и не ограничивает действия в backend | `pm-1`, `pm-2` |
| `ISS-15` | `P2` | Проекты / Чат | Чат проекта не создаётся автоматически при создании проекта | `pm-1` |
| `ISS-16` | `P2` | Проекты / UI | Нет визуализации Канбан и диаграммы Ганта (только плоские карточки/таблица) | `pm-2` |
| `ISS-17` | `P2` | Задачи | `GENERAL`-задача остаётся `IN_PROGRESS` после `submitResult` и отвергает `send-to-review` (`409`) | `snab-1` |
| `ISS-18` | `P2` | Снабжение / API | `PurchaseOrderActionRequest.idempotencyKey` nullable в DTO, но обязателен на сервере (`400`) | `snab-1` |
| `ISS-19` | `P2` | Снабжение / UI | `CreateOrderDialog` тупиковый без `SELECTED`-предложений; нет проверки контрольной суммы ИНН | `snab-2` |
| `ISS-20` | `P2` | Склад / Приёмка | Черновик приёмки требует `locationId` заранее на каждой строке | `sklad-1` |
| `ISS-21` | `P2` | Склад / UI & API | Нет фильтра `locationId` в `GET /api/wms/stock`, `expectedReceipts` обрезаны на 20 строках, N+1 в `rememberWmsNames` | `sklad-1`, `sklad-2` |
| `ISS-22` | `P2` | Склад / Перемещения | `CreateTransferDialog` не показывает доступный остаток и не фильтрует исходную ячейку | `sklad-2` |
| `ISS-23` | `P2` | Подразделения | Нет удаления/архивации подразделений | `admin` |
| `ISS-24` | `P2` | Уведомления | Нет кнопки и эндпоинта «Прочитать все» (`POST /api/notifications/read-all`) | `admin` |

---

## 1. Платформа и API-контракты

### `ISS-01` [P1] Default-поля (`status`, `version=0`, `verificationStatus`) опускаются в JSON-ответах
- **Где:** конфигурация Ktor `ContentNegotiation` `Json` на сервере (`GET /api/projects/{id}`, `GET /api/tasks/{id}`, `GET /api/demands/{id}`, `GET /api/supplier-offers`, `GET /api/suppliers/{id}`, `GET /api/wms/receipts`).
- **Наблюдение:** из-за `encodeDefaults = false` при дефолтных значениях (`version = 0`, `status = DRAFT`/`RECEIVED`, начальный `verificationStatus`) поля полностью отсутствуют в JSON. При тестировании `POST /api/tasks/18/accept` передача `version: 1` дала `409 Conflict`, тогда как `version: 0` сработала.
- **Задача:** включить `encodeDefaults = true` в серверной конфигурации `ContentNegotiation` `Json` (или явно аннотировать `@EncodeDefault(ALWAYS)` поля `status`, `version`, `verificationStatus`).

### `ISS-09` [P1] Несогласованный формат `dueDate` в API и сырой текстовый ввод в диалоге риска
- **Где:** `POST /api/tasks` vs `POST /api/projects/{id}/risks`, `TaskViewModel.kt:299`, `ProjectPlanTabs.kt:794-799`.
- **Наблюдение:** `POST /api/projects/5/risks` принимает дату `YYYY-MM-DD` (`"2026-11-26"`), а `POST /api/tasks` отвергает её с `400 VALIDATION_ERROR: "Invalid due date"` и требует ISO-8601 instant (`"2026-11-26T18:00:00Z"`). `TaskViewModel` дописывает `"T00:00:00Z"` на клиенте, а `ProjectPlanTabs.kt` использует сырой `ErpTextField` (`placeholder = "2026-12-01"`) вместо `ErpDateField`.
- **Задача:** принимать оба формата (`YYYY-MM-DD` и ISO-8601 instant) в `TaskService`/`ProjectPlanService` и заменить поле срока в диалоге риска на `ErpDateField`.

---

## 2. Администрирование, права и уведомления (`admin`)

### `ISS-10` [P1] `UserDto` и таблица пользователей не показывают роли; `EditUserDialog` затирает роли
- **Где:** `core/.../dto/UserDtos.kt` (`UserDto`), `server/.../service/UserService.kt`, `app/shared/.../ui/admin/AdminDataGrids.kt` (`UsersGrid`), `AdminViewModel.kt`, `AdminDialogs.kt` (`EditUserDialog`).
- **Наблюдение:** `GET /api/users` и `GET /api/users/{id}` возвращают `UserDto` без списка ролей (`permissions` пустой). В `UsersGrid` нет колонки «Роли» (в колонке «Права» всегда «По роли»). В `EditUserDialog` форма `editUserForm.roleIds` инициализируется пустой — если отметить «Изменить роли (заменяет текущий набор)», все чекбоксы сняты, и сохранение перезаписывает текущие роли пользователя.
- **Задача:** возвращать `roleIds`/`roleNames` в `UserDto`, добавить колонку «Роли» в `UsersGrid` и предзаполнять `EditUserForm.roleIds`.

### `ISS-11` [P1] Роли нельзя редактировать/удалять; права принимают произвольные строки
- **Где:** `server/.../route/RoleRoutes.kt`, `server/.../service/PermissionService.kt`, `app/shared/.../ui/admin/AdminScreen.kt` (`RolesTab`), `AdminDialogs.kt` (`CreateRoleDialog`, `AddPermissionDialog`).
- **Наблюдение:** в `RoleRoutes` есть только `GET /api/roles`, `POST /api/roles`, `GET /api/roles/{id}` — нет `PUT` и `DELETE` (ошибочные или тестовые роли вроде `QA_TYPO_143957` остаются навсегда). `PermissionService.create` не валидирует `resource`/`action`/`scope`, а в UI используются свободные текстовые поля без справочника допустимых прав.
- **Задача:** добавить обновление и удаление ролей (с проверкой активных назначений), справочник прав и серверную валидацию `resource:action`.

### `ISS-12` [P1] В «Журнале аудита» нет фильтра по пользователю, пагинации и полной локализации
- **Где:** `server/.../route/AuditRoutes.kt`, `app/shared/.../ui/admin/AdminViewModel.kt` (`loadAuditLogs`), `AdminScreen.kt` (`AuditTab`).
- **Наблюдение:** API `GET /api/audit` поддерживает `?userId=`, но UI не даёт выбрать пользователя, запрашивает фиксированный `limit=150` без пагинации и требует точного ввода верхнерегистровых кодов (`PURCHASE_ORDER`, `CONVERSATION_MESSAGE_SEND`). `localizedAuditAction`/`localizedAuditEntity` покрывают только ~9 действий и ~12 сущностей, оставляя `CONVERSATION_MESSAGE_SEND`, `CHAT_MESSAGE_SEND`, `SUBMIT_RESULT`, `SEND_TO_REVIEW`, `REVIEW_ACCEPT`, `SUPPLIER_OFFER`, `TRANSFER`, `PROJECT_STAGE`, `PROJECT_MILESTONE`, `PROJECT_RISK` на английском.
- **Задача:** добавить выбор пользователя и выпадающие списки фильтров в `AuditTab`, серверную пагинацию, полную русскую локализацию действий/сущностей и форматированный просмотр `payloadJson`.

### `ISS-23` [P2] Подразделения нельзя удалить или архивировать
- **Где:** `server/.../route/DepartmentRoutes.kt`, `app/shared/.../ui/admin/AdminScreen.kt` (`DepartmentsTab`).
- **Наблюдение:** доступны только `GET`, `POST`, `PUT /{id}` — нет `DELETE` или архивации.
- **Задача:** добавить архивацию/удаление подразделений с проверкой отсутствия привязанных сотрудников.

### `ISS-24` [P2] В уведомлениях нет «Прочитать все» и серверного счётчика непрочитанных
- **Где:** `server/.../route/NotificationRoutes.kt`, `app/shared/.../ui/notifications/NotificationScreen.kt`.
- **Наблюдение:** есть только `POST /api/notifications/{id}/read`, нет массовой отметки прочтения; счётчик непрочитанных считается на клиенте по текущей выборке (`limit=50`).
- **Задача:** добавить `POST /api/notifications/read-all` и кнопку «Прочитать все» в `NotificationScreen`.

---

## 3. Проекты, задачи и чаты (`pm-1`, `pm-2`)

### `ISS-05` [P1] Колонка «Руководитель» в списке проектов всегда отображает `«—»`
- **Где:** `GET /api/projects`, `app/shared/.../ui/projects/ProjectListScreen.kt:173-175`.
- **Наблюдение:** `GET /api/projects` возвращает `members: []`, а `ProjectListScreen.kt:174` ищет руководителя через `p.members.find { it.userId == p.ownerId }?.fullName ?: "—"`.
- **Задача:** возвращать `ownerName` в `GET /api/projects` или подставлять имя по `ownerId` из справочника пользователей (как в `TaskListScreen.kt:328`).

### `ISS-06` [P1] `ProjectDetailScreen` игнорирует право `projects:manage_members`
- **Где:** `app/shared/.../ui/projects/ProjectDetailScreen.kt:100-101` vs строка `179`.
- **Наблюдение:** `val canManageMembers = isOwner` запрещает не-владельцу с ролью `PM` (например `emu_pm2` в `projectId=5`) управлять участниками проекта в UI, хотя строка `179` проверяет `isOwner || currentUser.hasPermission("projects", "manage_members")`.
- **Задача:** заменить на `isOwner || currentUser.hasPermission("projects", "manage_members")`.

### `ISS-07` [P1] `GET /api/projects/{id}` не заполняет ФИО/логин участников, а `GET /api/users` закрыт для `PM`
- **Где:** `GET /api/projects/{id}`, `GET /api/users`, `GET /api/users/directory`.
- **Наблюдение:** под `emu_pm2` (`PM`) `GET /api/users` возвращает `403 FORBIDDEN ("Missing permission users:read_any")`, `GET /api/users/directory` без параметра даёт `400`, а `GET /api/projects/5` возвращает `members` только с `userId` (`userLogin`, `userName`, `role` равны `null`).
- **Задача:** заполнять `userLogin`, `userName` и `role` в `ProjectDto.members` на сервере при `GET /api/projects/{id}`.

### `ISS-08` [P1] Нет редактирования проектов и задач после создания
- **Где:** `server/.../route/ProjectRoutes.kt:1-117`, `TaskRoutes.kt:1-109`.
- **Наблюдение:** отсутствуют `PUT`/`PATCH` эндпоинты и кнопки в UI для изменения названия, описания, приоритета, сроков и критериев приёмки после создания проекта/задачи.
- **Задача:** добавить `PUT /api/projects/{id}` и `PUT /api/tasks/{id}` с проверкой `version`, аудитом и формами редактирования в UI.

### `ISS-14` [P2] Двухшаговый поток `DRAFT -> open` неудобен в UI и не ограничивает действия в API
- **Где:** `ProjectCreateDialog.kt:115`, `ProjectListScreen.kt:171`, `ProjectDetailScreen.kt:511-555`, `POST /api/projects/{id}/open`.
- **Наблюдение:** проект создаётся в статусе `DRAFT` («Черновик (откройте проект)»), но при этом сервер разрешает добавлять участников, этапы, вехи, риски, задачи (`16–19`) и писать в Project Chat без открытия проекта. Вызов `open` с пустым телом падает с `400`.
- **Задача:** добавить опцию «Создать и открыть» в `ProjectCreateDialog` либо явно разграничить ограничения `DRAFT`.

### `ISS-15` [P2] Чат проекта не создаётся автоматически при создании проекта
- **Где:** `ProjectService.create`, `ConversationRoutes.kt:130-138` (`POST /api/projects/{projectId}/chat`).
- **Наблюдение:** после `POST /api/projects` беседа проекта отсутствует в `GET /api/conversations` до отдельного вызова `ensureProjectChat`.
- **Задача:** создавать беседу `PROJECT` автоматически внутри `ProjectService.create`.

### `ISS-16` [P2] В проектах и задачах нет Канбан-доски и диаграммы Ганта
- **Где:** `ProjectDetailScreen.kt:161-216`, `ProjectPlanTabs.kt`, `TaskListScreen.kt`.
- **Наблюдение:** вкладки проекта (`OVERVIEW, TASKS, DISCUSSION, STAGES, RISKS, DEMANDS, DOCUMENTS, HISTORY`) отображают этапы/вехи плоскими карточками, а задачи — списком/таблицей.
- **Задача:** добавить Канбан-представление задач и таймлайн/Гант для этапов и контрольных точек проекта.

### `ISS-17` [P2] `GENERAL`-задача остаётся `IN_PROGRESS` после `submitResult` и отвергает `send-to-review`
- **Где:** `POST /api/tasks/{id}/result`, `POST /api/tasks/{id}/send-to-review` (`TaskService.kt:219-225`).
- **Наблюдение:** на задаче `T-2026-00016` отправка результата оставила статус `IN_PROGRESS`, а `send-to-review` вернул `409 CONFLICT: "Only reviewed tasks can be sent to review"`.
- **Задача:** сделать в UI/API явный путь завершения `GENERAL`-задачи без приёмки и понятную подсказку.

---

## 4. Снабжение, поставщики и заказы (`snab-1`, `snab-2`)

### `ISS-02` [P1] Тупик прав для `BUYER` на верификации поставщика, выборе предложения и утверждении заказа
- **Где:** `SupplierService.kt` (`selectOffer`, `verifySupplier`), `PurchaseOrderService.kt` (`approve`), матрица прав роли `BUYER`.
- **Наблюдение:** `emu_snab1`/`emu_snab2` (`BUYER`) могут создать поставщика (`SUP-2026-00005`), предложение (`OFF-2026-00004`) и черновик заказа, но:
  - `POST /api/suppliers/5/verification` → `403 (Missing permission suppliers:verify)`
  - `POST /api/supplier-offers/4/select` → `403 (Only the demand initiator can select the preferred offer)` (потребовалось вмешательство `admin`)
  - `POST /api/purchase-orders/2/approve` → `403 (Missing permission suppliers:approve_order)`
- **Задача:** настроить явную маршрутизацию согласований (уведомления/задачи инициатору потребности и утверждающему, отображение в UI кто именно должен подтвердить этап) и проверить права `PM` / `SUPPLY_CHIEF` на эти шаги.

### `ISS-18` [P2] `PurchaseOrderActionRequest.idempotencyKey` nullable в DTO, но обязателен на сервере
- **Где:** `core/.../dto/PurchaseOrderDtos.kt` (`PurchaseOrderActionRequest`), `POST /api/purchase-orders/{id}/send`.
- **Наблюдение:** пропуск `idempotencyKey` возвращает `400 VALIDATION_ERROR: "Idempotency key is required"`.
- **Задача:** сделать `idempotencyKey` обязательным в DTO или автоматически генерировать/подставлять на клиенте.

### `ISS-19` [P2] Тупик в `CreateOrderDialog` без `SELECTED`-предложений; нет проверки контрольной суммы ИНН
- **Где:** `PurchaseOrderDialogs.kt` (`CreateOrderDialog`, L46, L55-67), `SupplierRoutes.kt`, `SupplierService.kt:1069-1092`.
- **Наблюдение:** если нет предложений в статусе `SELECTED`, кнопка «Создать заказ» заблокирована без ссылки на вкладку предложений и без указания, кто должен выбрать предложение. При создании поставщика проверяется длина/уникальность ИНН, но не контрольная цифра (принят синтетический `5260987654`). Также `POST /api/suppliers` возвращает вложенный `SupplierCardDto`, а `GET /api/suppliers` — плоский `SupplierDto`.
- **Задача:** добавить переход из `CreateOrderDialog` к предложениям, проверку контрольной суммы ИНН и унифицировать/задокументировать ответы `/api/suppliers`.

---

## 5. Склад / WMS (`sklad-1`, `sklad-2`)

### `ISS-03` [P1] `expected-receipts` отдаёт заказы `PENDING_APPROVAL` и отрицательный `notAcceptedQuantity`
- **Где:** `GET /api/wms/expected-receipts`, `ReceiptService.kt` (`listExpectedReceipts` L79-85, `validateOrderAcceptsReceipt` L380-386).
- **Наблюдение:** в списке ожидаемых приёмок вернулся `PO-2026-00002` (`orderStatus: "PENDING_APPROVAL"`), но `POST /api/wms/receipts` по нему падает с `409 Conflict` («приёмка возможна только для `SENT`, `PARTIALLY_DELIVERED` или `DELIVERED`»). Кроме того, по `PO-2026-00001` строка 1 показала `orderedQuantity: 2.0000`, `acceptedQuantity: 3.0000`, `notAcceptedQuantity: -1.0000`.
- **Задача:** фильтровать `expected-receipts` только по разрешённым статусам заказа и ограничивать `notAcceptedQuantity >= 0` (отдельно помечая перепоставку).

### `ISS-04` [P1] `KEEPER` получает `403` на просмотр заказов поставщикам и поставщиков
- **Где:** `GET /api/purchase-orders`, `GET /api/purchase-orders/{id}`, `GET /api/suppliers`.
- **Наблюдение:** кладовщик (`emu_sklad1`) получает `403 Forbidden`, даже когда на него назначена задача приёмки (`taskId=17`) по `PO-2026-00002` и в `expected-receipts` указан `orderId`.
- **Задача:** выдать `KEEPER` доступ на чтение утверждённых/отправленных заказов (или складскую карточку заказа с поставщиком и датой поставки).

### `ISS-13` [P1] Склады и ячейки хранения нельзя редактировать, архивировать или удалять
- **Где:** `WmsRoutes.kt:18-38`, `WmsScreen.kt:314-397` (`WarehousesTab`, `LocationsTab`).
- **Наблюдение:** для `/api/wms/warehouses` и `/api/wms/locations` есть только `POST` и `GET` (нет `PUT`/`PATCH`/`DELETE`/архивации). Кроме того, `KEEPER` (`wms:manage`) может создавать новые склады (`TEST-WH`).
- **Задача:** добавить редактирование и архивацию складов и ячеек (с проверкой нулевых остатков) и ограничить создание складов административной/менеджерской ролью.

### `ISS-20` [P2] Черновик приёмки требует `locationId` на каждой строке до прибытия груза
- **Где:** `POST /api/wms/receipts`, `ReceiptService.kt` (`prepareLine` L270-271).
- **Наблюдение:** создание `DRAFT`-приёмки без `locationId` падает с `400 VALIDATION_ERROR`.
- **Задача:** сделать `locationId` необязательным для `DRAFT` и требовать его только при приёмке/проведении.

### `ISS-21` [P2] Нет фильтра `locationId` в остатках, `expectedReceipts` обрезаны на 20 строках, N+1 в именах WMS
- **Где:** `WmsRoutes.kt:116-124`, `WmsScreen.kt:388-392, 989-993`, `WmsPickers.kt:216-235`, `GET /api/wms/stock`, `GET /api/wms/transfers`.
- **Наблюдение:** клик по ячейке в `LocationsTab` фильтрует остатки только по `warehouseId` (нет `locationId` в `GET /api/wms/stock`). `WmsScreen.kt` показывает только первые 20 `expectedReceipts` без поиска/пагинации. DTO остатков и перемещений отдают голые ID без названий номенклатуры/единиц измерения/ячеек, из-за чего клиент делает N+1 запросов в `rememberWmsNames`.
- **Задача:** добавить `locationId` в `GET /api/wms/stock`, поиск/пагинацию в `expectedReceipts`, и денормализовать коды/названия номенклатуры, единиц и ячеек в DTO.

### `ISS-22` [P2] `CreateTransferDialog` не показывает доступный остаток и не исключает исходную ячейку
- **Где:** `WmsLogisticsTabs.kt:224-320` (`CreateTransferDialog`), `POST /api/wms/transfers`.
- **Наблюдение:** доступный остаток `availableQty` не отображается при выборе ячейки/позиции (ошибка `409` видна только после отправки), а в списке получателей при внутреннем перемещении остаётся `fromLocationId` (`400 Source and target locations must differ`).
- **Задача:** показывать доступный остаток в диалоге и исключать `fromLocationId` из списка ячеек назначения при внутреннем перемещении.

---

## Тестовые артефакты, созданные в ходе эмуляции
В локальной БД стенда были созданы следующие демонстрационные/тестовые сущности:
- Пользователи: `emu_sklad1` (`25`), `emu_sklad2` (`26`), `emu_snab1` (`27`), `emu_snab2` (`28`), `emu_pm1` (`29`), `emu_pm2` (`30`)
- Проект: `P-2026-00005` (`projectId=5`, «Эмуляция поставки А-100»), этап `id=7`, веха `id=5`, риск `id=1`, чат `conversationId=10`
- Задачи: `T-2026-00016` (`16`), `T-2026-00017` (`17`), `T-2026-00018` (`18`, `CLOSED`), `T-2026-00019` (`19`)
- Снабжение: поставщик `SUP-2026-00005` (`id=5`), предложение `OFF-2026-00004` (`id=4`), заказ `PO-2026-00002` (`id=2`)
- Склад: приёмка `RCP-2026-00012` (`id=12`), перемещение `TRF-2026-00008` (`id=8`), а также пробный склад `TEST-WH` (`id=3`) и ячейка `TEST-LOC` (`id=9`)
