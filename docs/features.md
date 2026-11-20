# Current behavior

This document describes the implementation currently present in the repository. It is not a statement that the whole ERP is complete.

## Addresses

- Backend and HTTP API: `http://localhost:8080`
- Frontend / Wasm client: `http://localhost:8081`
- The browser client calls the API only on port `8080`. The login page is opened on `8081`.

## Web interface

The web entry point mounts the Compose `App` in a browser viewport.

### Authentication

When the user is not authenticated, the app shows `LoginScreen` with:

- the **ERP «Авиаагрегат-Н»** title and **Вход в систему** subtitle;
- login and password fields;
- a **Войти** action calling `POST /api/auth/login`;
- a first-administrator action calling `POST /api/admin/bootstrap` while no users exist.

The login flow preserves entered data on validation and server errors and exposes loading/error states. `POST /api/auth/password` allows the current user to change the password with the current-password check and audit.

For emergency recovery, the server has a reserved `admin` account whose recovery credentials are checked in code. A successful recovery login recreates or repairs the database-backed administrator, active state, `ADMIN` role and global permission before issuing a normal session; recovery use is audited as `EMERGENCY_ADMIN_LOGIN`.

### Main scaffold, tasks and projects

After successful login the app opens a personal **Главная** in `MainScaffold`: assigned tasks, real due dates, tasks awaiting the user's review and loaded unread notifications. There are no fabricated readiness percentages or future-module metrics. **Уведомления** provides the persisted notification center, realtime updates without reload, mark-as-read and task navigation through `taskId` with a fresh server-side permission check; manual refresh preserves already received realtime events. **Администрирование** is shown only with applicable user/department/role/permission rights.

The navigation includes **Мои задачи**, **Проекты**, **Профиль** and real available modules. **Склад** requires `wms:read`; **Номенклатура и BOM** requires `bom:read_any`; **Снабжение** supports `demands:create`/`demands:read_any`. Wildcards follow the server rule. On narrow screens primary destinations are accompanied by a real «Ещё» menu for the remaining modules; the desktop rail scrolls when needed.

The task area uses real server data and supports:

- task list table using `Un7KCMPDataGrid` from the in-repo `app/un7datagrid` module (D-54), with number, title, status, priority, assignee and due date; the Wasm build and real data rows were checked in the browser;
- combined client-side filters by status, area (personal/project/department) and «Мои» for any task participant role;
- creation of personal, project and department tasks in the MVP-02 field set (no subtasks, checklists, tags or time tracking);
- task card with description, acceptance criteria, result and participants; on wide screens the discussion is shown next to the description, on narrow screens via «Описание»/«Обсуждение» tabs with the draft preserved;
- status commands (assign, accept, result, review accept/return, close, block/unblock, reopen, cancel) shown only for the status/role combinations the server accepts; server errors and 409-conflict reloads are surfaced;
- Task Chat with author/time, distinct compact styling for SYSTEM events, «Все / Сообщения / События» filter, idempotency-key sending and WebSocket delivery with cursor-based recovery and manual HTTP refresh; after the task is closed the discussion is read-only;
- the discussion view (`TaskChatPanel.kt`) shows an avatar with a name-derived gradient per author, groups consecutive messages of the same author, separates days with a divider taken from the raw `createdAt` string, marks the current user's own messages («вы») and highlights them, renders system events as one compact line with an icon, counts messages and events per filter and in the header, shows «Есть новые» only when the server reports newer messages, auto-scrolls to the newest message only while the reader is already at the bottom, and offers a 1–5 line composer with a character counter, a disabled send button for empty drafts and an «Отправка…» state

The project area uses real server data and supports:

- project list with number, title, status, owner, dates and record version;
- creation of a DRAFT project;
- project card with type, priority, owner, dates, opening data and members;
- working «Обзор», «Задачи» and «Обсуждение» tabs; the task tab shows only tasks linked to the selected project, their status, assignee and due date, a «Мои» filter and navigation to the task, with stale responses discarded when the selected project changes;
- opening a project with a reason and optimistic version check;
- closing a project with permission/version checks and server-side detection of unfinished related processes;
- adding and removing members with the appropriate server-side permission;
- project card tabs «Обзор», «Задачи», «Потребности», «Документы», «История». The overview shows only real counters of related records (tasks, open/completed tasks, demands, documents, members) — no readiness percentages, because their formula is not approved. The task tab reads `GET /api/projects/{id}/tasks?status=...` with server-side filtering and «Открыть» switches to the task card in the task section. The demand and document tabs are read-only lists served by their own endpoints. The history tab reads `audit_log` for `entity_type='PROJECT'`; the server returns ready Russian titles and the timestamp, and paginates via `limit` (1..500). «Этапы» and «Изделия» tabs are not shown: the entities do not exist yet (PRJ-08/09).

The **Чат** area unifies direct, group, project and task discussions without mixing their server-side access rules. Users can create direct/group conversations, open Project Chat from the unified list or project card, and use existing Task Chat history and realtime delivery. Task/project virtual IDs are kind-scoped, late responses are discarded after navigation, and read-only/observer permissions remain enforced. Conversation notifications use neutral text and open the relevant conversation after a fresh permission check.

A message can carry one browser-selected file. Multipart sending stores message metadata, attachment metadata, notifications and audit atomically; history and realtime DTOs include file name, size, author and download ID. Download rechecks current task/conversation/project access. The browser scenario uploaded and downloaded `chat-file-check.txt` with identical content. Closed tasks remain read-only.

The D-51 **Task Chat workspace** provides a 280dp dialog list, flexible message timeline and collapsible 320dp ERP context panel. It includes dialog search/filter/pins, last-message previews, unread/mention counters, replies, reactions, mentions, message search and navigation, limited bold/italic/code formatting, typing indicators and read marks. Drafts, pending files and scroll state are retained per task; initial view opens the latest message and new messages autoscroll only at the bottom or for the current author. Ctrl+Enter sends, Ctrl+F opens search and Esc cancels a reply. The context panel shows real task facts, participants, due date, project, attachments and linked documents, with a full-card action. No new UI dependency was added.

Task and project cards contain a real attachment section: the user picks a browser file, uploads/downloads/deletes it through the authorized API, with read-only behavior for closed tasks. WMS, BOM, demand and document screens are connected to their real API clients. Their full business acceptance is tracked separately; implementation presence is not presented as a completed ERP.

The WMS nomenclature list supports search by code, name, description and barcode. Opening an item loads `GET /api/wms/nomenclature/{id}/details`: exact physical/reserved/available totals, stock by warehouse and location, lots, serial numbers and the immutable movement journal with source document and author. The card follows the product-detail workflow of the reference `supply2` project without importing its database or duplicating the ERP nomenclature.

The demand card contains a reusable approval panel (`ApprovalsSection`): route list for the selected demand (`targetType=DEMAND`), route card with stages, ALL/ANY policy, assignees and decision history (author/time/comment), start of a route by template for a `PENDING_APPROVAL` demand with `approvals:start` and a per-attempt idempotency key, APPROVE/REJECT/RETURN decisions for the current stage (comment required for REJECT/RETURN on client and server) and route cancellation with a mandatory reason under `approvals:cancel`. After an action the panel reloads the route and the demand card refreshes so the final APPROVED status is visible. There is no global approval queue and no template editor in this increment.

The project card also contains real planning tabs. «Этапы» lists project stages with sequence, planned and actual dates, responsible and completion criteria; a stage goes `PLANNED → IN_PROGRESS → COMPLETED`, cancellation requires a reason and only a planned stage can be deleted. Milestones are nested under their stage with planned and actual dates and `reach`/`miss` commands. «Риски» lists project risks with category, probability, impact, criticality, responsible, response plan, due date and links to a stage and a project task; closing or accepting a risk requires an outcome text. There are no readiness percentages in any of these tabs — the calculation method is not approved (M.4, PRJ-18) — and criticality is entered explicitly instead of being derived from probability and impact (D-51). The plan is read by project members and changed by the owner or a holder of `projects:manage_plan`; the overview tab shows real plan counters (completed stages, reached milestones, open and critical risks).

All project tab endpoints enforce server-side access: a non-member without `projects:read_any` receives `403`, an unknown project `404`. The same project permission also opens an individual project task from the tab. Documents and their overview counter respect confidentiality and `documents:read_any`.

Relevant sources:

- [`App.kt`](../app/shared/src/commonMain/kotlin/org/example/project/App.kt)
- [`MainScaffold.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/main/MainScaffold.kt)
- [`ProjectsScreen.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectsScreen.kt)
- [`ProjectViewModel.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectViewModel.kt)
- [`ProjectTabs.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectTabs.kt)
- [`ProjectPlanTabs.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectPlanTabs.kt)

## HTTP client and data layer

`app/shared` uses Koin, Ktor Client and kotlinx.serialization. It includes:

- authentication API client with cookies and backend port `8080`;
- project and user API clients;
- UI state models with loading, empty and error states;
- `ApiResult` and DTO-based response handling.

Relevant sources:

- [`HttpClientProvider.kt`](../app/shared/src/commonMain/kotlin/org/example/project/data/HttpClientProvider.kt)
- [`AuthApiClient.kt`](../app/shared/src/commonMain/kotlin/org/example/project/data/AuthApiClient.kt)
- [`ProjectApiClient.kt`](../app/shared/src/commonMain/kotlin/org/example/project/data/ProjectApiClient.kt)
- [`SharedModule.kt`](../app/shared/src/commonMain/kotlin/org/example/project/di/SharedModule.kt)

## Reference selectors and receipt workflow

The demand, product-model and receipt forms use searchable selectors instead of manual identifier entry. Product designers and technologists are selected from the authenticated `GET /api/users/options` directory, which exposes only the ID and full name of active users and does not require `users:read_any`.

Demand creation provides searchable project, model, BOM revision and nomenclature selectors. Selecting nomenclature fills its unit of measure. Receipt creation provides warehouse, storage-location and nomenclature selectors, expected and received quantities, and lot validation for lot-tracked items. The receipt workspace loads documents through `GET /api/wms/receipts` with warehouse/status filters, opens a selected document without asking for its ID, and displays warehouse and nomenclature names. The list endpoint loads lines for all returned receipts in one query and retains the existing `wms:read` permission check.

## HTTP server

The Ktor Netty server listens on `0.0.0.0:8080` and uses PostgreSQL with Flyway migrations, generated jOOQ classes and Koin.

Implemented server areas include:

- local users, departments, roles and resource/action permissions;
- first-admin bootstrap with a database lock and transaction;
- login/logout/current user and password change with bcrypt and audit;
- stateful `erp_session` cookie with HttpOnly, SameSite=Lax and configurable Secure;
- explicit allowed CORS origins instead of wildcard credentials;
- project creation/opening/membership and record-level read permissions;
- task creation, assignment, execution, review, blocking and history;
- Task Chat messages with author/time, system event type, record-level access and idempotency keys;
- direct/group/project conversations with current membership checks, cursor history, realtime delivery, notifications and atomic message-file upload/download;
- a notification center with per-user lists, idempotent mark-as-read, per-user WebSocket delivery, cursor recovery and `taskId`/conversation navigation;
- approval templates and routes with sequential/parallel stages, ALL/ANY policies, manual assignees, cancellation, audit and target-version checks; the current target adapter supports demands, and the demand card carries a real approval panel (list, start, decisions, cancellation); other adapters and the template editor UI are incomplete.

The real D-10 flow has been exercised over HTTP: project → task → assignment → Task Chat → notification → review → close. Independent review confirmed that the original blockers were fixed. Search works for projects, tasks and users. Task Chat and notification-center WebSocket delivery, cursor recovery, session/Origin hardening and browser-client integration have passed server tests, JS/Wasm tests and real browser checks without page reload. The browser client stops reconnecting after REST 401/403/404 responses. External browser push through a Service Worker is not implemented.

### WMS server core

The `/api/wms` API supports warehouses, storage locations, nomenclature and barcodes, receipts and reception by source document (see the reception section below), stock balances, project/other reservations, internal/inter-warehouse transfers, issue and return operations with optimistic versions and idempotency checks. The combined WMS/BOM/DMS/demand suite passed all 61 tests, including concurrent stock operations, access checks and protection against duplicate posting. Browser checks confirmed real warehouse, location and nomenclature data in the WMS UI. This is not a complete WMS: transfer/issue UI, write-off, inventory and the remaining business operations are still incomplete.

Relevant sources:

- [`Application.kt`](../server/src/main/kotlin/org/example/project/Application.kt)
- [`ProjectRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ProjectRoutes.kt)
- [`TaskRoutes.kt`](../server/src/main/kotlin/org/example/project/route/TaskRoutes.kt)
- [`ChatRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ChatRoutes.kt)
- [`ConversationRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ConversationRoutes.kt)
- [`NotificationRoutes.kt`](../server/src/main/kotlin/org/example/project/route/NotificationRoutes.kt)
- [`WmsRoutes.kt`](../server/src/main/kotlin/org/example/project/route/WmsRoutes.kt)
- [`SupplierRoutes.kt`](../server/src/main/kotlin/org/example/project/route/SupplierRoutes.kt)
- [`PurchaseOrderRoutes.kt`](../server/src/main/kotlin/org/example/project/route/PurchaseOrderRoutes.kt)
- [`ReceiptRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ReceiptRoutes.kt)

## Not yet implemented

Complete WMS, procurement (claims, payments, approval thresholds), production, BOM, documents, approvals, calendar/Gantt, external Mail, external push delivery, Android and the full 100 business scenarios are not implemented. Work in progress is not presented as a working feature until its API/UI integration and checks pass.

## Визуальное оформление интерфейса

Веб-клиент использует собственную дизайн-систему (решение `D-47`): токены бренда, градиенты и переиспользуемые компоненты находятся в `app/shared/src/commonMain/kotlin/org/example/project/ui/theme/`.

- `Brand.kt` — палитра, градиенты и токены светлой/тёмной темы; `ErpTheme.kt` — цветовая схема, типографика и формы Material 3.
- `Components.kt` — `AppBackground`, `GlassCard`, `GradientButton`, `SoftButton`, `PageHeader`, `SectionHeader`, `PillBadge`, `Avatar`, `IconBadge`, `EmptyState`, `SkeletonLine`, `GradientSpinner` и собственный набор векторных иконок `ErpIcon`.
- Переключатель светлой и тёмной темы доступен в шапке и на экране профиля; состояние хранится в `ThemeController` (только в памяти вкладки).
- Главная, список задач, проекты, уведомления, профиль и страница входа переведены на новое оформление; экраны WMS, BOM, снабжения, документов и администрирования получили единую градиентную шапку страницы.
- `index.html` и `styles.css` содержат брендированную заставку загрузки, которая скрывается после появления канваса Compose.

Оформление не проверено визуальным прогоном в браузере в текущей среде: сборка Wasm и JDK в ней недоступны, изменения проверены только статически (баланс скобок, отсутствие неиспользуемых импортов). Перед приёмкой требуется прогон `:app:webApp:wasmJsBrowserDevelopmentRun`.

## Suppliers and offers (SUP-13, SUP-14)

Supplier cards live under the **Поставщики** navigation item and store requisites
(name, INN/KPP/OGRN, country, legal address, contacts, phone, email, site),
contacts, addresses, purchase categories, documents with validity dates and a
verification status (`NOT_VERIFIED`, `IN_REVIEW`, `VERIFIED`, `REJECTED`).
Duplicates are detected by INN among non-archived cards and rejected with `409`.

Offers are attached to a demand and to a verified supplier. An offer stores
currency, technical compliance (`UNKNOWN`, `FULL`, `PARTIAL`, `DEVIATION`),
document completeness, delivery time and terms, delivery cost, payment terms,
warranty, validity date and per-line quantity, unit price and VAT rate. Line
totals and offer totals are computed by the database (`supplier_offer_totals`),
the client never recalculates money.

`GET /api/demands/{id}/supplier-offers/comparison` returns one column per offer,
per-demand-line quantities, warnings (`EXPIRED`, `NOT_COMPLIANT`,
`COMPLIANCE_UNKNOWN`, `DOCUMENTS_MISSING`, `SUPPLIER_NOT_VERIFIED`,
`INCOMPLETE_COVERAGE`, `DELIVERY_UNKNOWN`, `DELIVERY_TOO_LONG`), the
`cheapestOfferId` and a separate `recommendedOfferId`.

Selection follows D-28: the demand initiator picks the preferred offer and must
give a reason of at least 10 characters. The server refuses the selection when
the offer expired, technical compliance is not confirmed, documents are not
confirmed, delivery time is unknown or misses the required date, or the supplier
is not verified — so choosing by the single lowest price is impossible. Only one
offer per demand can be preferred; the second selection returns `409`.

## Заказы поставщикам и частичные поставки (SUP-15, SUP-16)

Заказы поставщику живут в разделе **Заказы поставщикам** (`V26__purchase_orders.sql`).
Заказ создаётся только из предложения в статусе `SELECTED` и только у проверенного
поставщика; `offer_id` уникален, поэтому одно предложение даёт ровно один заказ и
обойти выбор инициатора нельзя. Количество в заказе можно только уменьшить
относительно предложения. Связь заказа с потребностями — многие-ко-многим
(`purchase_order_demands`), потребность выбранного предложения входит в неё всегда.

Жизненный цикл: `DRAFT → PENDING_APPROVAL → APPROVED → SENT`, затем
`PARTIALLY_DELIVERED`/`DELIVERED` по факту поставок и `CLOSED`; `CANCELLED`
возможен из `DRAFT`, `PENDING_APPROVAL`, `APPROVED` и `SENT` с обязательной
причиной. Отправка требует согласования: `send` возвращает `409`, пока пуст
`approved_at`. Согласование требует права `suppliers:approve_order`, а согласование
собственного заказа — дополнительно `suppliers:approve_order_any`.

Существенные условия — количество, ожидаемый срок поставки, условия оплаты и адрес
доставки. Их изменение требует `changeReason`; если заказ уже не черновик, он
возвращается в `PENDING_APPROVAL`, выставляется `reapproval_required`, а в историю
пишется `REAPPROVAL_REQUIRED`, поэтому согласованный заказ нельзя отправить
поставщику с молча изменёнными условиями.

Поставки частичные: `purchase_deliveries`/`purchase_delivery_lines` принимают
количество не больше остатка по позиции, одна позиция заказа встречается в поставке
один раз, а сам заказ должен быть в статусе `SENT` или `PARTIALLY_DELIVERED`.
После регистрации `delivered_quantity` пересчитывается из SQL-суммы по поставкам в
статусе `REGISTERED`, а статус заказа становится `PARTIALLY_DELIVERED` или
`DELIVERED`. Отмена поставки пересчитывает количества обратно, возвращает заказ в
`SENT`, если не поставлено ничего, и пишет `DELIVERY_CANCELLED` в историю.

Суммы считает база (view `purchase_order_totals` и `GENERATED`-столбец `line_total`):
без НДС, НДС, с НДС, заказанное и поставленное количество. Деньги и количества
передаются строками `NUMERIC(19,4)`, клиент их не пересчитывает. Все команды
идемпотентны: ключ обязателен, хеш тела — `sha256(operation|payload)`, повтор ключа
с другим телом даёт `409`. Номера `PO-YYYY-00001` и `PD-YYYY-00001` выдаются под
`pg_advisory_xact_lock`.

Сознательно не реализовано: оплата, счета-фактуры и претензии (вне согласованного
объёма), суммовые пороги и маршруты согласования (§9.7 — открытый вопрос,
выдуманные суммы не вводились). Связь с приёмкой WMS реализована отдельным блоком —
см. следующий раздел. Претензии и оценка поставщика по фактам (SUP-17) остаются
открытыми.

## Приёмка на склад по основаниям (WMS-PROC-001, D-49)

Приёмка вынесена в `ReceiptService` (`/api/wms/receipts*`) и всегда имеет основание:
заказ поставщику, счёт (`INVOICE`), накладная/УПД (`DELIVERY_NOTE`), возврат,
перемещение, начальный остаток или прочий документ (`V27__wms_receipt_basis.sql`).
Для заказа, счёта, накладной, возврата и перемещения номер документа-основания
обязателен (`400`), и только этим трём видам разрешено ссылаться на заказ поставщику.

Один документ-основание даёт ровно одну приёмку: частичный уникальный индекс
`uq_receipts_source_document (source_type, source_document_no)` не считает
`CANCELLED`, поэтому повторная приёмка той же накладной возвращает `409`. Приёмка по
заказу может быть частичной и многократной, но не может превысить заказанное:
ожидаемое количество не вводится клиентом, оно считается как остаток заказа минус
уже принятое. Позиция заказа обязательна; для однопозиционного заказа сервер
подставляет её автоматически. Если указана только поставка, заказ выводится из неё.
Проверка выполняется дважды — при создании и при проведении, потому что между
черновиком и проведением могла пройти другая приёмка; при проведении позиция заказа
блокируется, чтобы конкурентные приёмки не превысили остаток. Превышение даёт `409`.
Одна позиция заказа встречается в приёмке один раз (`400 duplicate_order_line`).

Расхождения «ожидалось / принято» фиксируются по строке как `SHORTAGE` или
`SURPLUS` с величиной `DECIMAL(19,4)`; расхождение без `discrepancyReason`
отклоняется (`400`). Фактически принятое (`receivedQuantity`) может отличаться от
ожидаемого и задаётся явно.

Остаток появляется только после проведения: `POST /api/wms/receipts/{id}/post`
блокирует документ, сверяет версию, запрещает повторное проведение и «нулевое»
проведение, пишет движение `RECEIPT`, увеличивает физический остаток и пересчитывает
принятое по поставке в одной транзакции. Проведённый документ защищён от перезаписи
и удаления триггером `wms_guard_receipt_mutation` (`V28__wms_receipt_guard.sql`).
Поздняя связь с заказом, поставкой или номером накладной выполняется через
`POST /api/wms/receipts/{id}/links`: пишется append-only журнал `receipt_links`,
остаток не пересоздаётся, повторная привязка тех же документов и конфликт номера
документа дают `409`.

Ожидаемые поставки отдаёт `GET /api/wms/expected-receipts?purchaseOrderId` на основе
view `purchase_order_receipt_status` («заказано / поставлено / принято / осталось»).
Список приёмок фильтруется сервером: `GET /api/wms/receipts?purchaseOrderId&status`.

Права: чтение `wms:read`, создание, проведение и связывание — `wms:post`. В UI вкладка
«Приёмка» показывает список документов, форму создания с основанием и обязательным
номером документа, бейдж расхождения в карточке и диалог поздней связи.

Поздняя связь меняет только документ: строки приёмки не сопоставляются с позициями
заказа автоматически, поэтому принятое по такому заказу не попадает в
`purchase_order_receipt_status`. Сопоставление строк задаётся при создании приёмки
полем `purchaseOrderLineId` — угадывать соответствие по номенклатуре сервер не
стал, это было бы выдуманным правилом.

Не реализовано в этом блоке: настраиваемый контроль качества по категории и
номенклатуре с карантином (`WMS-37`) и претензии к поставщику.

