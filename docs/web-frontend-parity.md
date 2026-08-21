# Web Frontend Parity Inventory

> **Implementation status (2026-08-08):** The active operational route set described here is implemented under `web-frontend/` and packaged into the Spring Boot JAR. Sections that describe a target or blocker are retained as design history; the current resolution is summarized in sections 12 and 13.

## 1. Scope and evidence

This document inventories the JavaFX frontend that exists in the current checkout and maps it to a target browser route structure. It is based on the following source surfaces, not on screenshots or assumptions:

- Bootstrap and active navigation: `DashboardApp`, `LoginController`, `MainController`, `login.fxml`, `main.fxml`
- Active screens: controllers under `controller/tab`, `NetworkTabController`, `CommandStatusPanelController`, `DashboardPcManagementModule`, and `DashboardManagementModule`
- Dormant editor: `DashboardController`, the remaining `controller/dashboard` modules, and `dashboard.fxml`
- HTTP, STOMP, and file behavior: `ApiClient`, `DashboardService`, `DashboardDataService`, `DashboardLayoutService`, `DashboardPdfService`, backend controllers, `SecurityConfig`, and `WebSocketConfig`

API paths below are relative to the configured `/api/v1` base unless an absolute path is shown. The target routes are a web routing plan; JavaFX currently uses tabs and dialogs, not URLs.

### Runtime classification

- **Active**: reachable after `DashboardApp -> login.fxml -> LoginController -> main.fxml`.
- **Wrapper-only**: implemented but not opened by the current sidebar because parent menu rows only expand their child list.
- **Legacy**: belongs to `dashboard.fxml`. `LoginController` explicitly bypasses this screen and loads `main.fxml`.
- **Unreferenced**: no Java loader or `fx:include` reference exists in the current frontend source.

## 2. Confirmed entry flow and access contract

1. `DashboardApp` always opens `login.fxml`.
2. A successful `POST /auth/login` stores the returned JWT and role in the singleton `ApiClient`.
3. `LoginController.showDashboard()` loads `main.fxml`, not `dashboard.fxml`.
4. `MainController` opens the classroom status tab by default.
5. The active shell polls alerts every 60 seconds. It does not instantiate `DashboardRealtimeModule`.

### Backend role rules

| Contract | Confirmed behavior |
|---|---|
| Public | `/auth/login`, agent registration/polling endpoints, health, Swagger, and `/ws/**` are permitted without a user JWT. |
| Generic GET | `ADMIN`, `USER`, and `VIEWER` may call `GET /api/v1/**`, unless method security is stricter. |
| Generic writes | `ADMIN` and `USER` may call non-GET `/api/v1/**`, unless method security is stricter. |
| ADMIN-only families | `/admin/**`, `/users/**`, `/groups/**`, `/commands` read/write, `/commands/to-ip`, `/file-chunks*`, and `/file-distribution/**` are restricted by `@PreAuthorize("hasRole('ADMIN')")`. |
| User settings | `/settings/{key}` is available to every authenticated role and is scoped to the current username. |
| Alerts | Alert reads follow generic GET. Acknowledge operations allow `ADMIN` or `USER`. |

The active JavaFX menu only removes its `ADMIN_ONLY` entries for `VIEWER`. Therefore `USER` can see several screens whose APIs are ADMIN-only. This is an existing contract mismatch, not a proposed web behavior.

## 3. Target route map

| Target web route | JavaFX source | Current JavaFX exposure | Effective backend access | State |
|---|---|---|---|---|
| `/login` | `login.fxml`, `LoginController` | Public | Public login only | Active |
| `/classrooms` | `classroom-status-tab.fxml`, `ClassroomStatusController` | ADMIN, USER, VIEWER | Reads: all roles; add/power: ADMIN | Active |
| `/classrooms/:groupId` | Detail panel inside `classroom-status-tab.fxml` | ADMIN, USER, VIEWER | Reads: all roles; power: ADMIN | Active nested view |
| `/network` | `network-tab.fxml`, `NetworkTabController` | ADMIN, USER, VIEWER | All roles, GET only | Active |
| `/network/:groupId` | Detail panel plus `network-traffic-table.fxml` | ADMIN, USER, VIEWER | All roles, GET only | Active nested view |
| `/pc/monitor` | `pc-management-view.fxml`, `DashboardPcManagementModule` | ADMIN, USER | Status read: all roles; commands/rollout: ADMIN | Active child tab |
| `/pc/commands` | `command-status-panel.fxml`, `command-table.fxml`, `CommandStatusPanelController` | ADMIN, USER | ADMIN only | Active child tab; role mismatch |
| `/pc/tokens` | `token-mgmt-tab.fxml`, `TokenMgmtTabController` | ADMIN, USER | ADMIN only | Active child tab; role mismatch |
| `/pc/broken` | `mgmt-broken-pc-section.fxml`, `DashboardManagementModule` | ADMIN, USER | Read: all roles; writes: ADMIN, USER | Active child tab |
| `/reports` | `report-view.fxml` and report fragments, `DashboardManagementModule` | ADMIN, USER | All roles, GET only | Active; VIEWER is hidden despite API access |
| `/settings/ip-ranges` | `mgmt-ip-range-section.fxml`, `DashboardManagementModule` | ADMIN, USER | Read: all roles; writes: ADMIN, USER; per-PC command: ADMIN | Active child tab |
| `/settings/teacher-pcs` | `mgmt-teacher-section.fxml`, `DashboardManagementModule` | ADMIN, USER | Read: all roles; writes: ADMIN, USER | Active child tab |
| `/settings/system` | `system-settings-tab.fxml`, `SystemSettingsController` | ADMIN, USER | Personal alert filter: all roles; password/users: ADMIN | Active child tab; mixed-role screen |
| `/dashboard-editor` | `dashboard.fxml`, `DashboardController`, dashboard modules | No current entry | Mixed by endpoint; editor writes generally ADMIN, USER | Legacy; product decision required |

`/pc` and `/settings` should be layout routes that redirect to their first visible child. This replaces the JavaFX parent-tab wrappers without reproducing their current unreachable state.

## 4. Active route parity criteria

### `/login`

**Source:** `DashboardApp`, `login.fxml`, `LoginController`, `ApiClient`

**API:** `POST /auth/login`

**Desktop-local behavior:** stores server host, port, remembered username, and remember-user flag in Java Preferences. It does not persist the password.

**Acceptance criteria:**

- Validate nonblank host, username, and password and a numeric port in the 1-65535 range before dispatch.
- Send `username` and `password`, consume `username`, `token`, and `role`, and attach `Authorization: Bearer <token>` to subsequent API requests.
- Preserve remembered username and server selection without retaining the password.
- Show server validation, authentication, malformed response, connection, 401, and 403 errors without leaving a stale loading state.
- Redirect authenticated users to `/classrooms`; reject protected routes when no valid token is present.
- Decide same-origin versus selectable API host before implementation because arbitrary host selection is constrained by browser CORS and mixed-content rules.

### Authenticated shell and global alerts

**Source:** `main.fxml`, `MainController`, `DashboardAlertApiClient`, `DashboardAlertPopupBuilder`

**APIs:** `GET /alerts?limit=200`, `POST /alerts/{id}/ack`, `POST /alerts/ack-all`

**Refresh:** alert snapshot every 60 seconds. No active-shell WebSocket subscription.

**Acceptance criteria:**

- Render the current role label, role-aware sidebar, menu search, global PC/classroom search, route tabs or equivalent breadcrumbs, alert badge/menu, toasts, and logout confirmation.
- Global search must navigate to `/classrooms` and apply the term; clearing it must clear the classroom search state.
- Filter alert types using the same hidden-type set used by system settings.
- VIEWER may read alerts but cannot acknowledge them; ADMIN and USER can acknowledge one or all.
- A 401 clears the authenticated state and returns to `/login`; route transitions and polling must be cancelled on logout.
- Poll updates must preserve open detail drawers, search/filter state, scroll position, and selected rows.

### `/classrooms` and `/classrooms/:groupId`

**Source:** `classroom-status-tab.fxml`, `ClassroomStatusController`

**APIs:**

- `GET /dashboard/classrooms`
- `GET /dashboard/pcs`
- `GET /dashboard/classrooms/{groupId}/pcs`
- `POST /commands` for group WOL/shutdown
- `POST /groups` for classroom creation

**Refresh and files:** 30-second polling; CSV save; JavaFX printer job; OS fullscreen.

**Acceptance criteria:**

- Provide table and grid modes, classroom and status filters (`전체`, `온라인`, `명령 실행중`, `경고`, `오프라인`), refresh, CSV download, print, and fullscreen.
- The grid must support up to six classroom cards per row at the current desktop width, consume the available row width without a residual right gutter, and keep student-PC cells aligned without layout jitter.
- Polling must update existing cards in place when classroom identity/order is unchanged; cards, scroll position, and the detail drawer must not disappear or flash during refresh.
- Classroom detail must show online/warning/offline counts, list/grid PC views, teacher/student separation, seat/name, power/heartbeat time, and a closeable responsive side panel.
- Group power commands require a confirmation, prevent duplicate in-flight submission for the same group/type, disable the source control while pending, and restore it on every success/failure path.
- VIEWER must not see mutation controls. USER behavior cannot be finalized until the role conflict is resolved because the active UI exposes controls while `/commands` and `/groups` require ADMIN.
- CSV data must match the visible filtered table. Browser print and fullscreen must be initiated by a user gesture.

### `/network` and `/network/:groupId`

**Source:** `network-tab.fxml`, `network-traffic-table.fxml`, `NetworkTabController`, `DashboardService`

**APIs:**

- `GET /dashboard/traffic`
- `GET /dashboard/config/traffic-thresholds`
- `GET /dashboard/classrooms`
- `GET /dashboard/classrooms/{groupId}/traffic/pcs`

**Refresh and files:** `MainController` starts 15-second polling; CSV save; JavaFX printer job; fullscreen.

**Acceptance criteria:**

- Render inbound/outbound history, threshold-derived room load, filters (`전체`, `정상`, `이상`), room table, and room PC detail.
- Preserve chart history while refreshing; cap series history the same way as JavaFX and avoid recreating the chart on each request.
- A room is abnormal at more than 80% of the configured classroom threshold; PC load uses the configured PC threshold.
- CSV, print, and fullscreen behavior must remain user-triggered and must not interrupt polling.
- All three roles may access this read-only route.

### `/pc/monitor`

**Source:** `pc-management-view.fxml`, `pcmgmt-view-controls.fxml`, `pcmgmt-pc-table.fxml`, `pcmgmt-command-section.fxml`, `DashboardPcManagementModule`

**APIs:**

- `GET /status/all`
- `POST /commands`, `GET /commands?taskId={id}`
- `POST /file-chunks?...`, `POST /file-chunks/assemble?...`
- `POST /file-distribution/jobs`
- `GET /file-distribution/jobs`, `/file-distribution/jobs/{id}`, `/file-distribution/jobs/{id}/events`
- Dynamic `GET /agent/uploads/{id}/download` returned by command output

**Local/native behavior:** local file/folder pickers; 8 MiB chunk upload; direct save to local paths; bundled/system `vncviewer.exe` launch; writes UltraVNC configuration under `C:\ProgramData`; remote file-list polling; collected ZIP downloads.

**Acceptance criteria:**

- Preserve views (`전체 PC`, `강의실별`, `교사 PC만`), status/search filters, row and select-all selection, summary counts, and manual refresh.
- Preserve command set and payloads: `PCON`, `REBOOT`, `SHUTDOWN`, `RUN_PS1`, `RUN_FILE_TRANSFER`, `LIST_FILES`, `COLLECT_FILES`, `REMOTE_CONTROL_START`, and `REMOTE_CONTROL_STOP`.
- Power commands require the same destructive confirmation. An empty row selection means the currently filtered scope, matching JavaFX.
- Script and distribution uploads must keep per-chunk SHA-256, progress, failure recovery, and final assembly semantics.
- File transfer must retain multi-file/folder selection, per-item run flag, destination mode (`DOWNLOADS`, `DESKTOP`, `CUSTOM`), and script execution options.
- Remote collection must retain paged remote browsing, USB-only filtering, 4-second browser refresh while open, cancellation, per-PC progress, bounded parallel downloads, timeout, and result reporting.
- Agent rollout must show upload, job creation, recent jobs, job detail, and events and must never report queued targets as completed targets.
- ADMIN is required for command, history, upload, and rollout APIs. Do not ship this route for USER with controls that only fail as 403.
- Native VNC launch is not browser-portable; parity remains blocked until a helper/protocol or browser VNC design is chosen.

### `/pc/commands`

**Source:** `command-status-panel.fxml`, `command-table.fxml`, `CommandStatusPanelController`

**APIs:** `GET /commands?status=QUEUED,DELIVERED,ACKED,COMPLETED,FAILED&size=500`; dynamic collected-file download URLs.

**Refresh and files:** polls every 10 seconds; ZIP save dialog for collection results.

**Acceptance criteria:**

- Preserve search, status grouping, multi-select command-type filter, room filter, six sort modes, reset, summary counts, and latest 500-row limit.
- Preserve status interpretation for queued/delivered/acked/completed/failed, verification state, SCMATE reset state, and stale snapshot warnings.
- Detail view must show progress steps, metadata, stdout, stderr, failure details, transfer/run options, and a result download only when a valid URL is present.
- Polling must update the selected detail in place and must not reset filters or close the drawer.
- Route is ADMIN-only according to `CommandController`, despite current JavaFX visibility for USER.

### `/pc/tokens`

**Source:** `token-mgmt-tab.fxml`, `TokenMgmtTabController`

**APIs:**

- `GET/PUT /admin/install-key`
- `GET/PUT /agent/registration-status`
- `GET /admin/agents`
- `PUT /admin/agents/{mac}/revoke`
- `DELETE /admin/agents/{mac}`
- `GET /agent/config/download`

**File/clipboard behavior:** copies install key to the system clipboard; saves `config.json`.

**Acceptance criteria:**

- Preserve classroom, agent-state, token-state, and text filters plus per-row and select-all selection.
- Rotate install key only after confirmation and immediately display the returned key/preview state.
- Toggle registration with explicit current/next state and recover button state on failure.
- Revoke and delete support multiple selected agents; delete uses a destructive confirmation.
- Config download must use the authenticated response filename/content and clipboard copy must occur on a user gesture.
- Entire route is ADMIN-only. The current USER-visible JavaFX screen is not an acceptable web permission model.

### `/pc/broken`

**Source:** `mgmt-broken-pc-section.fxml`, `DashboardManagementModule`

**APIs:**

- `GET /dashboard/classrooms`
- `GET /dashboard/pcs/broken`
- `POST /dashboard/pcs/broken`
- `PUT /dashboard/pcs/broken/{macAddress}`
- `DELETE /dashboard/pcs/broken/{macAddress}`

**Acceptance criteria:**

- Preserve room/status/search filters and list MAC, IP, room, state, reason, and management actions.
- Registration requires IP or MAC, a positive integer seat number, and a reason; send `seatNumber` as the explicit placement key.
- Editing changes the reason only. Clearing broken state requires confirmation and refreshes both this list and classroom placement on the next status fetch.
- Non-numeric seat input must produce validation feedback rather than an uncaught exception.
- ADMIN and USER may mutate; VIEWER may read at the backend but the active JavaFX menu currently hides the route.

### `/reports`

**Source:** `report-view.fxml`, `mgmt-performance-section.fxml`, `mgmt-report-section.fxml`, `mgmt-report-date-dialog-content.fxml`, `DashboardManagementModule`

**APIs:** `GET /status/performance`; `GET /reports/pdf?type={WEEKLY|MONTHLY}&fromDate={date}&toDate={date}`

**File behavior:** saves returned PDF bytes with a generated filename.

**Acceptance criteria:**

- Render PC online ratio, command queue/failure/stale metrics, token/cache metrics, pressure badges, and updated time.
- Support manual metric refresh and weekly/monthly report generation with validated date range.
- Download the server PDF as a browser attachment and expose server errors instead of creating an empty file.
- Preserve backend behavior that reverses an inverted start/end date range.
- Current menu parity is ADMIN and USER. Backend GET allows VIEWER, so final route visibility requires an explicit product decision.

### `/settings/ip-ranges`

**Source:** `mgmt-ip-range-section.fxml`, `mgmt-individual-control-dialog.fxml`, `DashboardManagementModule`

**APIs:**

- `GET /dashboard/classrooms`
- `GET/POST /ip-ranges`, `DELETE /ip-ranges/{id}`
- `GET /dashboard/classrooms/{groupId}/pcs`
- `POST /commands/to-ip`

**Acceptance criteria:**

- Filter by classroom and list range ID, classroom, start IP, end IP, and description.
- Validate required classroom/start/end IPv4 values and display duplicate/overlap errors returned by the API.
- Delete requires confirmation.
- Individual-control view must list PCs in the selected range and issue the same IP-targeted commands.
- Reads are available to all roles, range writes to ADMIN/USER, and individual commands to ADMIN only; controls must follow those capabilities.

### `/settings/teacher-pcs`

**Source:** `mgmt-teacher-section.fxml`, `DashboardManagementModule`

**APIs:** `GET /dashboard/classrooms`; `GET/POST /dashboard/classrooms/{groupId}/teacher-devices`; `DELETE /dashboard/classrooms/teacher-devices/{id}`

**Acceptance criteria:**

- Preserve classroom filtering and list number, classroom, IP, MAC, description, and delete action.
- Require classroom and valid teacher IP; keep MAC and description optional.
- Delete requires confirmation and refreshes the selected/all-classroom result consistently.
- ADMIN and USER may mutate. VIEWER read access exists at the API but the active menu hides this route.

### `/settings/system`

**Source:** `system-settings-tab.fxml`, `SystemSettingsController`

**APIs:**

- `GET/PUT /settings/alert-type-filter`
- `PUT /admin/change-password`
- `GET/POST /users`, `DELETE /users/{id}`

**Acceptance criteria:**

- Alert filter must preserve all current type mappings, including the grouped performance types, and update the active alert filter after save.
- Password change requires current password, at least eight characters, matching confirmation, and clears fields on success.
- User management lists username/email/role and supports ADMIN, USER, VIEWER creation plus confirmed deletion.
- The page must render sections by capability: the username-scoped alert filter for all authenticated roles, administrator password and user management only for ADMIN.
- Current JavaFX exposes the whole route to USER although two sections are ADMIN-only. Web parity must not reproduce predictable 403 controls.

## 5. Realtime and refresh parity

| Surface | Current mechanism | Required web parity |
|---|---|---|
| Active classroom status | REST polling every 30 seconds | Same interval initially; in-place cache update and request deduplication. |
| Active network | REST polling every 15 seconds | Same interval while route is visible; stop when unmounted. |
| Active command history | REST polling every 10 seconds | Same interval while visible; preserve detail/filter state. |
| Active alerts | REST polling every 60 seconds | Same interval globally after login. |
| PC monitor, token, broken, report, settings | Initial load plus manual/route-reactivation refresh | Match current triggers; do not add unbounded background polling. |
| Legacy dashboard editor | `/ws/websocket` STOMP client subscribes to `/topic/dashboard` and `/topic/alerts`; 30-second REST fallback; 5-second WS refresh throttle; reconnect after 30 seconds | Only required if `/dashboard-editor` is retained. Authentication and origin policy must be fixed before browser use. |

The backend registers `/ws` with SockJS and `allowedOriginPatterns("*")`. Spring Security permits `/ws/**`, and no inbound-channel JWT authorization is implemented. The JavaFX STOMP client sends an Authorization header, but the server configuration shown here does not validate it at the WebSocket channel. This is a blocker for exposing the same broker to browsers.

## 6. File and native behavior map

| JavaFX behavior | Source | Browser-equivalent acceptance | Blocked? |
|---|---|---|---|
| Classroom/network CSV save | `ClassroomStatusController`, `NetworkTabController` | Generate from the visible filtered data and trigger a UTF-8 download. | No |
| Table/view print | Same controllers | User-triggered print stylesheet and `window.print()`. | No |
| PDF report save | `DashboardManagementModule`, `DashboardPdfService` | Authenticated binary fetch followed by browser download. | No |
| Config JSON save | `TokenMgmtTabController` | Authenticated attachment download. | No |
| Install-key clipboard | `TokenMgmtTabController` | Clipboard API on explicit click with fallback copy UI. | No |
| Script/agent artifact upload | `ApiClient.uploadScriptFile` | Keep 8 MiB chunking, SHA-256 per chunk, assembly call, progress, cancellation/error state. | No |
| File/folder transfer upload | `DashboardPcManagementModule` | Multi-file input and directory input where supported; preserve relative paths and per-item run options. | Partial browser support |
| Remote collection auto-save | `DashboardPcManagementModule` | Browser download per result or user-selected File System Access destination; cannot silently write arbitrary local paths. | Product decision |
| VNC viewer launch/config | `DashboardPcManagementModule` | Requires custom protocol/native helper or a server-mediated browser VNC implementation. | Yes |
| Dashboard layout local backup | `DashboardLayoutService` | JSON download/upload or migration to existing backend layout-backup APIs. | Product decision |
| Legacy image widget upload | `DashboardWidgetModule` | Multipart image upload and authenticated/authorized image retrieval. | No, if editor retained |

## 7. Legacy dashboard editor

`dashboard.fxml` and `DashboardController` implement a separate dashboard editor that is no longer loaded after login. It is materially larger than the active shell and must not be silently treated as an active parity requirement.

**Proposed route if retained:** `/dashboard-editor`

**Roles implemented by the legacy controller:**

- ADMIN: dashboard editor plus PC, network, command, and settings tabs.
- USER: dashboard tab only.
- VIEWER: read-only dashboard plus network; editor controls and PC/command/settings are removed.

**Major APIs:**

- `GET /dashboard/refresh`, `GET /dashboard/traffic`, traffic thresholds and per-room traffic
- `GET/POST/DELETE /dashboards`, panel list/create/update/delete and batch position updates
- `GET/POST/PUT/DELETE /dashboard/texts`, text rename/restore
- `GET/POST/PUT/DELETE /dashboard/images`, multipart image upload, `GET /files/{filename}`
- `GET /dashboard/classrooms/{groupId}/pcs`, `POST /commands`, `POST/PUT/DELETE /groups`
- Alerts and performance endpoints already listed above

**Meaningful behavior:** dashboard selection/creation/deletion, canvas pan/zoom/fit, edit/focus/dashboard-only modes, grid/snap, drag/resize, smart guides, undo/redo, classroom cards and commands, text/image widgets, network/disconnect/performance panels, panel persistence, local JSON layout backup/restore, PDF dialog, 30-second snapshot refresh, and STOMP updates.

**Acceptance criteria if retained:**

- Persist dashboard/panel identity and coordinates without rounding drift across reload, zoom, drag, resize, undo, and redo.
- Preserve dashboard-scoped text/images and panel assignment; optimistic edits must reconcile with the API result.
- Viewer mode must be read-only in both controls and network requests.
- Layout backup semantics must be chosen explicitly: the JavaFX screen uses local JSON files even though backend layout-backup endpoints exist.
- WebSocket subscriptions must be authenticated and origin-restricted before production exposure.
- `WEEKLY_LAST` must not be accepted as parity-complete until the backend produces last week rather than falling through to the current-week branch.

## 8. Controller and module inventory

| Controller/module | Runtime owner | Target route or responsibility |
|---|---|---|
| `LoginController` | Active | `/login`; validation, login, endpoint selection, remembered username, shell transition. |
| `MainController` | Active | Authenticated layout, route/menu state, global search, alerts, logout. |
| `ClassroomStatusController` | Active | `/classrooms`, `/classrooms/:groupId`. |
| `NetworkTabController` | Active | `/network`, `/network/:groupId`. |
| `CommandStatusPanelController` | Active and legacy reusable panel | `/pc/commands`; command polling and detail/result download. |
| `TokenMgmtTabController` | Active | `/pc/tokens`. |
| `SystemSettingsController` | Active | `/settings/system`. |
| `DashboardPcManagementModule` | Active and legacy reusable view | `/pc/monitor`; PC selection, commands, transfer, collection, VNC, rollout. |
| `DashboardManagementModule` | Active sections and legacy aggregate | `/pc/broken`, `/reports`, `/settings/ip-ranges`, `/settings/teacher-pcs`; legacy agent aggregate. |
| `PcControlTabController` | Wrapper-only | Composite PC wrapper. Current sidebar opens each child directly; web uses `/pc/*` layout routing. |
| `SettingsTabController` | Wrapper-only | Composite settings wrapper. Its `system` factory is an empty notice, unlike the active `SystemSettingsController`. |
| `DashboardController` | Legacy | `/dashboard-editor` if retained; owns editor shell and legacy tab composition. |
| `NetworkPanelController` | Legacy | Reusable editor network panel using network chart/table fragments. |
| `DashboardAlertApiClient` | Active and legacy | Alert snapshot/unread/ack API adapter. |
| `DashboardAlertPopupBuilder` | Active and legacy | Alert popup rendering and action wiring. |
| `DashboardAlertCoordinator` | Legacy | Coordinates legacy alert badge/popup state. |
| `DashboardAlertSupport` | Legacy/helper | Alert parsing, labeling, and display support. |
| `DashboardCanvasModule` | Legacy | Canvas bounds, pan/zoom/edit interaction. |
| `DashboardClassroomModule` | Legacy | Classroom panel CRUD, PC grid, group management, group commands. |
| `DashboardDisconnectStatusPanelModule` | Legacy | Derives connection-change rows from classroom PC snapshots. |
| `DashboardGridSettings` | Legacy | Grid min/max/column settings state. |
| `DashboardInteractionService` | Legacy | Drag/resize interaction helpers. |
| `DashboardLayoutActionModule` | Legacy | Local JSON layout backup/restore orchestration. |
| `DashboardNetworkPanelModule` | Legacy | Wraps `NetworkPanelController` as a dashboard panel. |
| `DashboardOperationsMetricsPanelModule` | Legacy | Performance panel and `/status/performance` refresh. |
| `DashboardRealtimeModule` | Legacy | STOMP connect, topic subscriptions, reconnect. |
| `DashboardResourcePanelModule` | Legacy | Resource list and panel creation controls. |
| `DashboardRightPanelModule` | Legacy | Editor settings/right-panel controls. |
| `DashboardSelectionHandles` | Legacy | Resize handles for selected canvas nodes. |
| `DashboardSessionActionModule` | Legacy | Legacy logout/session navigation. |
| `DashboardSmartGuides` | Legacy | Drag alignment guides. |
| `DashboardStatusSummarySupport` | Legacy/helper | Aggregate status summary formatting. |
| `DashboardUIService` | Legacy | Toast, zoom, mode, and shell UI updates. |
| `DashboardViewModel` | Legacy | Dashboard snapshot/cache/edit state. |
| `DashboardWidgetModule` | Legacy | Text/image widget CRUD, styling, upload, and resize. |

## 9. FXML inventory

All 47 files under `frontend/src/main/resources/fxml` are accounted for below.

| FXML | Runtime classification | Web mapping |
|---|---|---|
| `login.fxml` | Active | `/login`. |
| `main.fxml` | Active | Authenticated shell around all active routes. |
| `classroom-status-tab.fxml` | Active | `/classrooms` and detail drawer. |
| `network-tab.fxml` | Active | `/network` and detail drawer. |
| `network-traffic-table.fxml` | Active and legacy | PC traffic detail on `/network`; also used by legacy network panel. |
| `pc-management-view.fxml` | Active | `/pc/monitor` shell and command side panel. |
| `pcmgmt-view-controls.fxml` | Active fragment | `/pc/monitor` filters/view controls. |
| `pcmgmt-pc-table.fxml` | Active fragment | `/pc/monitor` PC table. |
| `pcmgmt-command-section.fxml` | Active fragment | `/pc/monitor` command and rollout controls. |
| `command-status-panel.fxml` | Active and legacy | `/pc/commands` shell/detail drawer. |
| `command-table.fxml` | Active and legacy fragment | `/pc/commands` table. |
| `token-mgmt-tab.fxml` | Active | `/pc/tokens`. |
| `mgmt-broken-pc-section.fxml` | Active | `/pc/broken`. |
| `report-view.fxml` | Active | `/reports` composition. |
| `mgmt-performance-section.fxml` | Active fragment | `/reports` metrics. |
| `mgmt-report-section.fxml` | Active fragment | `/reports` generation controls. |
| `mgmt-report-date-dialog-content.fxml` | Active dialog | Report date/type modal on `/reports`. |
| `mgmt-ip-range-section.fxml` | Active | `/settings/ip-ranges`. |
| `mgmt-individual-control-dialog.fxml` | Active dialog | IP-range PC control modal. |
| `mgmt-teacher-section.fxml` | Active | `/settings/teacher-pcs`. |
| `system-settings-tab.fxml` | Active | `/settings/system`. |
| `pc-control-tab.fxml` | Wrapper-only | `/pc` route outlet; current parent row does not open it. |
| `settings-tab.fxml` | Wrapper-only | `/settings` route outlet; current parent row does not open it. |
| `dashboard.fxml` | Legacy | `/dashboard-editor` if retained. |
| `dashboard-statusbar.fxml` | Legacy fragment | Editor connectivity, role, clock, summary bar. |
| `resource-panel-left.fxml` | Legacy fragment | Editor resource/panel palette. |
| `classroom-panel-card.fxml` | Legacy fragment | Editor classroom panel. |
| `classroom-grid-settings-dialog.fxml` | Legacy dialog | Editor classroom grid settings. |
| `network-wrapper-panel.fxml` | Legacy fragment | Editor network panel frame. |
| `network-panel.fxml` | Legacy fragment | Editor gauges/chart/table composition. |
| `network-chart.fxml` | Legacy fragment | Editor network chart. |
| `operations-metrics-panel.fxml` | Legacy fragment | Editor performance panel. |
| `disconnect-status-panel.fxml` | Legacy fragment | Editor connection event panel. |
| `mgmt-view.fxml` | Legacy aggregate | Legacy settings tab composition. |
| `mgmt-agent-section.fxml` | Legacy aggregate fragment | Agent management duplicated by active `/pc/tokens`. |
| `pdf-report-dialog-content.fxml` | Legacy dialog | Legacy editor PDF dialog. |
| `widget-text-add-dialog-content.fxml` | Legacy dialog | Add text widget. |
| `widget-text-header.fxml` | Legacy fragment | Text widget formatting/delete controls. |
| `widget-image-item.fxml` | Legacy fragment | Image widget edit/delete controls. |
| `widget-resize-handle.fxml` | Legacy fragment | Widget resize handle. |
| `pc-transfer-select-dialog.fxml` | Dormant code path | Loaded only by an uncalled private transfer-selection method; active transfer uses the side panel. |
| `pc-transfer-item-row.fxml` | Dormant code path | Row fragment for the uncalled transfer dialog method. |
| `classroom-detail-content.fxml` | Unreferenced | Do not migrate unless a caller is restored. |
| `group-command-confirm-content.fxml` | Unreferenced | Current group confirmation is constructed through `Dialogs`; do not migrate as a separate screen. |
| `network-main-pc-table.fxml` | Unreferenced | Do not migrate; active network table is built from `network-traffic-table.fxml`. |
| `pc-command-confirm-dialog.fxml` | Unreferenced | Current command confirmation uses the `/pc/monitor` side panel; do not migrate separately. |
| `placeholder-tab.fxml` | Unreferenced | Do not migrate. |

## 10. Endpoint-to-screen trace

| Endpoint family | JavaFX consumer | Role enforced by backend |
|---|---|---|
| `/auth/login` | Login | Public |
| `/alerts`, `/alerts/unread-count`, `/alerts/*/ack`, `/alerts/ack-all` | Main shell and legacy editor | Read all; ack ADMIN/USER |
| `/settings/alert-type-filter` | Username-scoped system settings and alert filtering | ADMIN/USER/VIEWER |
| `/dashboard/classrooms`, `/dashboard/pcs`, `/dashboard/classrooms/*/pcs` | Classroom, PC, settings, legacy panels | GET all |
| `/dashboard/traffic`, `/dashboard/config/traffic-thresholds`, `/dashboard/classrooms/*/traffic/pcs` | Active and legacy network | GET all |
| `/groups*` | Classroom creation and legacy group edit | ADMIN |
| `/commands`, `/commands/to-ip` | Classroom power, PC monitor, command history, IP-range control, legacy classroom cards | ADMIN |
| `/status/all`, `/status/performance` | PC monitor, reports, performance panels | GET all |
| `/file-chunks`, `/file-chunks/assemble` | Script/artifact chunk upload | ADMIN |
| `/file-distribution/jobs*` | Agent rollout | ADMIN |
| `/agent/uploads/{id}/download` | Command result and remote collection download | ADMIN |
| `/admin/install-key`, `/admin/agents*` | Token management and legacy agent section | ADMIN |
| `/agent/registration-status`, `/agent/config/download` | Token management and legacy agent section | ADMIN |
| `/dashboard/pcs/broken*` | Broken PC | GET all; writes ADMIN/USER |
| `/ip-ranges*` | IP range settings | GET all; writes ADMIN/USER |
| `/dashboard/classrooms/*/teacher-devices` | Teacher PC settings | GET all; writes ADMIN/USER |
| `/reports/pdf` | Active report and legacy PDF service | GET all |
| `/users*`, `/admin/change-password` | System settings | ADMIN |
| `/dashboards*` and panel children | Legacy editor | GET all; writes ADMIN/USER |
| `/dashboard/texts*`, `/dashboard/images*`, `/files/{filename}` | Legacy widgets | GET all; writes ADMIN/USER |
| `/dashboard/refresh` | Legacy dashboard snapshot | GET all |

## 11. Cross-route acceptance gates

- Every protected fetch attaches the JWT and handles 401 and 403 distinctly.
- Navigation and action visibility derive from backend capabilities, not only from the role label.
- Pollers stop when their route unmounts and never overlap an in-flight request for the same resource.
- Refreshes reconcile by stable IDs and do not clear the DOM before replacement data arrives.
- Loading, empty, stale, partial-failure, forbidden, offline, and retry states exist for each screen.
- Destructive actions use an explicit confirmation and are idempotency-protected in the client while pending.
- Tables preserve filters, sort, selection, scroll, and open detail across background refresh.
- Responsive verification covers the current 1920x1080 master-PC view and a smaller 1366x768 classroom admin view.
- The classroom grid specifically passes resize/zoom tests without a one-pixel oscillation, scrollbar-induced column count changes, right-side residual gutter, or flashing cards.
- File uploads/downloads are tested with large files, cancellation, 401/403, interrupted network, duplicate click, and server-side assembly failure.
- A parity route is complete only after API contract tests plus browser interaction tests cover the criteria in its section.

## 12. Resolved decisions and remaining boundary

1. The web route/action matrix follows backend authority: VIEWER is read-only, USER receives allowed operational writes, and ADMIN-only command, token, account, distribution, and destructive controls are hidden and server-protected.
2. Browser remote control uses a server-mediated noVNC relay with a short-lived session and one-time ticket. The relay validates target CIDR/port and closes the agent session when the viewer exits.
3. Production is same-origin. Development origins are explicit through `CORS_ALLOWED_ORIGINS`; wildcard browser origins are not used.
4. STOMP CONNECT validates JWT, session expiry is tracked, and SEND/SUBSCRIBE revalidate the authenticated session.
5. Browser JWT state is kept in `sessionStorage`; expiry or a 401 clears the session and returns to login. The existing token remains non-refreshable by design.
6. Browser file operations use upload/download semantics. Arbitrary silent writes to administrator-local paths and automatic native process launch are intentionally not reproduced.
7. Broken-PC registration validates numeric seat values and supports explicit PC/seat placement instead of deriving placement from a failure memo.
8. The dormant `dashboard.fxml` visual editor remains outside the operational web release. Its exclusion does not remove any route reachable from the active JavaFX `main.fxml` shell.
9. React Router's published RSC-mode advisory is not reachable in this client-only Vite SPA because it does not use RSC or server actions. It remains tracked until an unaffected supported release is available.
10. Multi-PC shutdown and reboot use one `MULTI` command request and a batched database write. Wake-on-LAN remains grouped by classroom because each request must use the matching subnet relay.
11. Browser-created ZIP uploads are limited to 128 MiB total. Larger folders must be compressed before selection so the browser does not buffer an unbounded folder in memory.
12. Agent rollout supports all online PCs, one classroom, or an explicit online-PC selection. Explicit selections are sent as validated, deduplicated MAC addresses instead of being widened to the first selected classroom.

## 13. Implemented release boundary

The release covers `/login`, `/classrooms`, `/pcs`, `/commands`, `/tokens`, `/broken`, `/network`, `/reports`, `/files`, `/settings/classrooms`, `/settings/users`, and `/settings/system`, including classroom and PC state, command history/issuance, tokens, broken PCs, traffic, reports, file distribution/collection, users, system settings, and browser VNC. Legacy `/pc/*` and split classroom-setting paths redirect to these active routes. The dormant visual dashboard editor remains a separately approved future product scope.
