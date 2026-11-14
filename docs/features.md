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

### Main scaffold, tasks and projects

After successful login the app opens a personal **Главная** in `MainScaffold`: assigned tasks, real due dates, tasks awaiting the user's review and loaded unread notifications. There are no fabricated readiness percentages or future-module metrics. **Уведомления** provides the persisted notification center and mark-as-read; **Администрирование** is shown only with applicable user/department/role/permission rights.

The navigation includes **Мои задачи**, **Проекты**, **Профиль** and real available modules. **Склад** requires `wms:read`; **Номенклатура и BOM** requires `bom:read_any`; **Снабжение** supports `demands:create`/`demands:read_any`. Wildcards follow the server rule. On narrow screens primary destinations are accompanied by a real «Ещё» menu for the remaining modules; the desktop rail scrolls when needed.

The task area uses real server data and supports:

- task list table using `Un7KCMPDataGrid` from `ComposeDataGrid 0.2.10-1`, with number, title, status, priority, assignee and due date; the Wasm build and real data rows were checked in the browser;
- creation of personal, project and department tasks in the MVP-02 field set (no subtasks, checklists, tags or time tracking);
- task card with description, acceptance criteria, result and participants; on wide screens the discussion is shown next to the description, on narrow screens via «Описание»/«Обсуждение» tabs with the draft preserved;
- status commands (assign, accept, result, review accept/return, close, block/unblock, reopen, cancel) shown only for the status/role combinations the server accepts; server errors and 409-conflict reloads are surfaced;
- Task Chat with author/time, distinct compact styling for SYSTEM events, «Все / Сообщения / События» filter, idempotency-key sending and WebSocket delivery with cursor-based recovery and manual HTTP refresh; after the task is closed the discussion is read-only.

The project area uses real server data and supports:

- project list with number, title, status, owner, dates and record version;
- creation of a DRAFT project;
- project card with type, priority, owner, dates, opening data and members;
- opening a project with a reason and optimistic version check;
- closing a project with permission/version checks and server-side detection of unfinished related processes;
- adding and removing members with the appropriate server-side permission.

Task and project cards contain a real attachment section: the user picks a browser file, uploads/downloads/deletes it through the authorized API, with read-only behavior for closed tasks. WMS, BOM, demand and document screens are connected to their real API clients. Their full business acceptance is tracked separately; implementation presence is not presented as a completed ERP.

The demand card contains a reusable approval panel (`ApprovalsSection`): route list for the selected demand (`targetType=DEMAND`), route card with stages, ALL/ANY policy, assignees and decision history (author/time/comment), start of a route by template for a `PENDING_APPROVAL` demand with `approvals:start` and a per-attempt idempotency key, APPROVE/REJECT/RETURN decisions for the current stage (comment required for REJECT/RETURN on client and server) and route cancellation with a mandatory reason under `approvals:cancel`. After an action the panel reloads the route and the demand card refreshes so the final APPROVED status is visible. There is no global approval queue and no template editor in this increment.

Relevant sources:

- [`App.kt`](../app/shared/src/commonMain/kotlin/org/example/project/App.kt)
- [`MainScaffold.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/main/MainScaffold.kt)
- [`ProjectsScreen.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectsScreen.kt)
- [`ProjectViewModel.kt`](../app/shared/src/commonMain/kotlin/org/example/project/ui/projects/ProjectViewModel.kt)

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
- a notification center with per-user lists and idempotent mark-as-read;
- approval templates and routes with sequential/parallel stages, ALL/ANY policies, manual assignees, cancellation, audit and target-version checks; the current target adapter supports demands, and the demand card carries a real approval panel (list, start, decisions, cancellation); other adapters and the template editor UI are incomplete.

The real D-10 flow has been exercised over HTTP: project → task → assignment → Task Chat → notification → review → close. Independent review confirmed that the original blockers were fixed. Search works for projects, tasks and users. Task Chat WebSocket delivery, cursor recovery, session/Origin hardening and browser-client integration have passed server tests, JS/Wasm tests and a real browser check without page reload. The browser client stops reconnecting after REST 401/403/404 responses. Realtime notification-center delivery and external browser push are not implemented.

### WMS server core

The `/api/wms` API supports warehouses, storage locations, nomenclature and barcodes, receipts with partial quantities and atomic posting, stock balances, project/other reservations, internal/inter-warehouse transfers, issue and return operations with optimistic versions and idempotency checks. The combined WMS/BOM/DMS/demand suite passed all 61 tests, including concurrent stock operations, access checks and protection against duplicate posting. Browser checks confirmed real warehouse, location and nomenclature data in the WMS UI. This is not a complete WMS: transfer/issue UI, write-off, inventory and the remaining business operations are still incomplete.

Relevant sources:

- [`Application.kt`](../server/src/main/kotlin/org/example/project/Application.kt)
- [`ProjectRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ProjectRoutes.kt)
- [`TaskRoutes.kt`](../server/src/main/kotlin/org/example/project/route/TaskRoutes.kt)
- [`ChatRoutes.kt`](../server/src/main/kotlin/org/example/project/route/ChatRoutes.kt)
- [`NotificationRoutes.kt`](../server/src/main/kotlin/org/example/project/route/NotificationRoutes.kt)
- [`WmsRoutes.kt`](../server/src/main/kotlin/org/example/project/route/WmsRoutes.kt)

## Not yet implemented

Complete WMS, procurement, production, BOM, documents, approvals, calendar/Gantt, external Mail, external push delivery, Android and the full 100 business scenarios are not implemented. Work in progress is not presented as a working feature until its API/UI integration and checks pass.

## Визуальное оформление интерфейса

Веб-клиент использует собственную дизайн-систему (решение `D-47`): токены бренда, градиенты и переиспользуемые компоненты находятся в `app/shared/src/commonMain/kotlin/org/example/project/ui/theme/`.

- `Brand.kt` — палитра, градиенты и токены светлой/тёмной темы; `ErpTheme.kt` — цветовая схема, типографика и формы Material 3.
- `Components.kt` — `AppBackground`, `GlassCard`, `GradientButton`, `SoftButton`, `PageHeader`, `SectionHeader`, `PillBadge`, `Avatar`, `IconBadge`, `EmptyState`, `SkeletonLine`, `GradientSpinner` и собственный набор векторных иконок `ErpIcon`.
- Переключатель светлой и тёмной темы доступен в шапке и на экране профиля; состояние хранится в `ThemeController` (только в памяти вкладки).
- Главная, список задач, проекты, уведомления, профиль и страница входа переведены на новое оформление; экраны WMS, BOM, снабжения, документов и администрирования получили единую градиентную шапку страницы.
- `index.html` и `styles.css` содержат брендированную заставку загрузки, которая скрывается после появления канваса Compose.

Оформление не проверено визуальным прогоном в браузере в текущей среде: сборка Wasm и JDK в ней недоступны, изменения проверены только статически (баланс скобок, отсутствие неиспользуемых импортов). Перед приёмкой требуется прогон `:app:webApp:wasmJsBrowserDevelopmentRun`.
