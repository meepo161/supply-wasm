# Design

## Source of truth

- Status: Active
- Last refreshed: 2026-11-26
- Primary product surfaces: desktop ERP/WMS, role workspaces for engineering, procurement and warehouse, project supply balance, task list, task card and Task Chat.
- Evidence reviewed: `docs/ТЗ_ERP_WMS_CHAT_TASK_MANAGER.md` (M.2, M.3, M.5, M.8, N), `docs/decisions.md` (D-64, D-65), `app/shared/src/commonMain/kotlin/org/example/project/ui/{home,demand,projects,wms}/` and the shared theme components.

## Brand

- Personality: industrial, calm, precise and trustworthy.
- Trust signals: clear status text, visible deadlines and responsibility, predictable actions and no invented indicators.
- Avoid: decorative gradients dominating data, oversized cards, consumer-task-manager styling and colour as the sole carrier of meaning.

## Product goals

- Goals: enable each role to see what needs attention, what can be done now, and who owns the next step; make project material coverage traceable from specification to partial receipt.
- Non-goals: simulate Kanban, analytics, saved views or server pagination before their API and business rules exist.
- Success signals: dense desktop scanability; useful narrowing by text, status, task area and participation; cards remain legible on small screens.

## Personas and jobs

- Primary personas: leading engineer, procurement specialist, warehouse keeper and production operator.
- User jobs: engineer passes a specification and decides between offers; procurement reserves stock and buys the shortage; keeper receives actual quantities; operator completes only assigned production work.
- Key contexts of use: a desktop workstation is primary; a phone is a secondary, card-oriented view.

## Information architecture

- Primary navigation: a permission-filtered rail with `Главная`, `Проекты`, unified `Снабжение`, `Склад`, and `Задачи`; separate supply pages must link back to the relevant project, demand and next owner.
- Core routes/screens: role-aware home, project overview with supply balance, demand workspace, expected-receipt queue, task table/list, task card and Task Chat workspace.
- Content hierarchy: page title and primary creation action, compact discovery/filter toolbar, task records, task card.

## Design principles

- Put task records before decorative summaries.
- Make filters explainable and reversible.
- Use a table for cross-task comparison on desktop and cards only where the viewport cannot carry a table.
- Tradeoff: local text filtering is clearly limited to loaded records until the backend provides paginated search/filtering.

## Visual language

Dial: ENERGY 1 / RHYTHM 2 / MOTION 1.

- Palette (`ui/theme/Brand.kt`, `ErpTheme.kt`): factory blue `#1C5FC9` is the only action and selection colour; graphite and cool grey neutrals carry the data; signal amber `#E0A21B` marks only the current place (active navigation item, keyboard focus in the dark rail). Module and status hues are muted and never become button fills.
- No decorative gradients, colour blobs, glass or glow: `Gradients.*` keep their `List<Color>` API but are solid fills.
- Action hierarchy, one primary per screen or dialog:
  - primary: `GradientButton` / theme `Button`, solid blue, white text;
  - secondary: `SoftButton` / theme `OutlinedButton`, outlined;
  - tertiary: `TextButton`, `ErpIconButton` (refresh, close, back);
  - dangerous: `GradientButton(gradient = Gradients.Danger)` or `confirmGradient = Gradients.Danger`, the only red button (cancel a document, delete).
- Screens import `Button`, `OutlinedButton`, `TextButton` and `Tab` from `org.example.project.ui.theme`, not from Material 3: the wrappers give 6 dp corners, a hand cursor and neutral unselected tabs.
- Selection (filters, sub-sections) uses `ErpFilterChip`; metrics stay neutral and turn red only when the value is alarming.
- Shape: tags 4 dp, buttons and fields 6 dp, cards 8-10 dp, dialogs 12 dp. Status and count tags are rectangular "labels", the repeated identity motif.
- Elevation: only dialogs cast a shadow; cards sit flat with a 1 dp border.
- Every custom clickable uses `Modifier.erpInteractive`: hand cursor plus a 2 dp focus ring shown for keyboard focus only.
- Narrow screens: list and card areas use `ErpSplitRow`; header actions and chip rows use `FlowRow` so nothing is squeezed to one letter per line.
- Color: use existing semantic status and priority accents; every status retains its textual label.
- Typography: compact, readable table headers and values; titles are not oversized.
- Spacing/layout rhythm: compact toolbar and rows; no large empty regions between control and result.
- Shape/radius/elevation: reuse existing theme components, with restrained surfaces.
- Motion: hover may affirm a selectable record, never hide state.
- Imagery/iconography: use existing ERP glyphs only as action affordances.

## Components

- Existing components to reuse: `Un7KCMPDataGrid`, `GradientButton`, `SoftButton`, `FilterPill`, `TaskCard`, `ErrorMessageCard`, loading and empty states.
- New/changed components: task-list search field and an explicit desktop table/mobile-card selection.
- Variants and states: loading, error, no data, empty filtered result and disabled refresh.
- Token/component ownership: shared theme owns colours, icons and buttons; the task feature owns its filters and record selection.

## Accessibility

- Target standard: readable labels and non-colour-only task state.
- Keyboard/focus behavior: native text field and clickable controls retain Compose focus semantics.
- Contrast/readability: semantic status labels are always visible alongside accents.
- Reduced motion and sensory considerations: no new animation is required for the task index.

## Responsive behavior

- Supported breakpoints/devices: desktop first; table from 760dp, cards below 760dp.
- Layout adaptations: filtering and search remain available on both layouts; the table never becomes an unusable horizontal wall on a phone.
- Touch/hover differences: card is the touch affordance; desktop grid supports record click.

## Interaction states

- Loading: skeleton rows.
- Empty: distinguish no available tasks from no matches.
- Error: show the API error without hiding currently loaded records.
- Success: opening a record leads to its existing task workspace.
- Disabled: refresh is disabled while initial data load is in progress.
- Offline/slow network: existing error state remains visible; client filtering never claims server completeness.

## Content voice

- Tone: direct, operational and factual.
- Terminology: use `Задачи`, `Исполнитель`, `Срок`, `Статус`; do not call this route `Мои задачи` because it contains more than personal assignments.
- Microcopy rules: state the scope of counts and empty results plainly.

## Implementation constraints

- Framework/styling system: Kotlin/Wasm Compose, Material 3 and the existing ERP theme.
- Design-token constraints: no new design system or dependencies.
- Performance constraints: current task API returns the permitted full list; do not imply it supports thousands of records. Server pagination/filtering is a prerequisite for that scale.
- Compatibility constraints: preserve the existing routes, DTOs, permissions and task-action server commands.
- Test/screenshot expectations: common filtering tests and Wasm/JS compilation; browser verification when the local stack is available.

## Open questions

- [ ] Define API contract for server-side task pagination, filtering, ordering and saved views before presenting them as available.
- [ ] Approve the task status-transition and permissions matrix before adding mass operations or drag-and-drop.
- [ ] Decide which of the target Task Manager views (Kanban, calendar) follows the MVP table.
