# Web Frontend Deployment

## Runtime model

- `web-frontend/` is a React 19 and TypeScript client-only SPA.
- `backend:bootJar` runs the locked frontend build and packages `dist/` under `BOOT-INF/classes/static/`.
- Spring MVC forwards supported browser routes to `/index.html`; REST endpoints and hashed assets are not intercepted.
- Production uses one HTTPS origin for the browser, REST API, STOMP, and VNC relay.

## Local development

Start the backend on port `8888`, then run:

```powershell
cd web-frontend
npm ci
npm run dev
```

The development URL is `http://localhost:5173`. Set `VITE_API_BASE_URL` only when the API is not available at the configured development default. Add the exact development origin to `CORS_ALLOWED_ORIGINS`.

## Production routes

The active routes are `/classrooms`, `/pcs`, `/commands`, `/tokens`, `/broken`, `/network`, `/reports`, `/files`, `/settings/classrooms`, `/settings/users`, and `/settings/system`. Classroom and network detail views use an ID suffix. Legacy `/pc/*` and split classroom-setting URLs redirect to the active routes so saved links remain usable.

## Verification

```powershell
cd web-frontend
npm ci
npm run typecheck
npm test
npm run build

cd ..
.\gradlew.bat :backend:test -PskipWebFrontendBuild=true --no-daemon
.\gradlew.bat :backend:clean :backend:bootJar --no-daemon
jar tf backend\build\libs\backend-0.0.1-SNAPSHOT.jar | Select-String 'BOOT-INF/classes/static/index.html'
```

The locked production dependency audit currently reports a React Router RSC-mode advisory. This application is a client-only Vite SPA and does not enable React Server Components or server actions, so the affected execution path is absent. Do not force-downgrade to a release with the older open-redirect/XSS advisories; update when a supported unaffected router release is available and rerun the full suite.

## Docker and NAS

```powershell
deploy\scripts\build-and-export.bat
```

The resulting `deploy/classroom-dashboard.tar` contains the backend and web frontend together. On the NAS, retain the previous image tag and database backup, load the tar, recreate only `app`, then verify:

```bash
curl -fsS http://127.0.0.1:8888/actuator/health
curl -fsS http://127.0.0.1:8888/ | head
docker compose ps
docker compose logs --tail=200 app
```

The Synology reverse proxy must forward WebSocket upgrade headers for `/ws/**`. Keep `NGINX_ACCEL_REDIRECT=false` unless a separately managed Nginx has the matching internal upload location.

## Access model

Login keeps the short-lived access token in browser session storage. When the operator selects `자동 로그인`, the backend also sets a rotating 90-day refresh cookie with `HttpOnly`, `SameSite=Strict`, and the auth-only path. Production HTTPS must set `JWT_REFRESH_COOKIE_SECURE=true`. `아이디 기억` is independent and stores only the username; no password is persisted by the application. Logout clears both the browser session and refresh cookie.

| Role | Web capability |
|---|---|
| `VIEWER` | Read-only classroom, PC, network, reports, and permitted status views |
| `USER` | Read access plus allowed operational updates such as broken-PC and alert acknowledgement |
| `ADMIN` | Command issuance, tokens, users, distribution, remote control, and destructive settings |

The backend remains the authority. Hidden controls are usability safeguards, not the security boundary.

## Browser remote control

Remote control creates a bounded backend session, waits for the agent VNC endpoint, issues a one-time browser ticket, and relays noVNC traffic over authenticated WebSocket. Configure `app.remote-control.*` only with the classroom CIDRs and VNC ports that are actually allowed. Closing the dialog, unloading the page, timeout, or ticket failure closes the server-side session.

The classroom detail drawer includes an admin-only screen wall for up to 50 student PCs. It starts four view-only noVNC streams per second with low JPEG quality and high compression, releases all sessions while the browser tab is hidden, and sends one bounded batch-close request on page exit. Production keeps `REMOTE_CONTROL_MAX_SESSIONS=64` so the wall does not consume every relay slot.

## Browser file operations

Single files, including a ZIP prepared in Windows, are uploaded without the browser rebuilding them. Multiple files or a selected folder are automatically packed into a ZIP only up to 128 MiB total; larger folders must be compressed first and selected as one ZIP file. This limit prevents an administrator browser from loading an unbounded classroom folder into memory.

## Release checks

- Login and expired-session redirect work through the production origin.
- Direct refresh works on every documented route.
- The classroom grid remains stable at 1920x1080 and 1366x768 without scrollbar oscillation or card flashing.
- Mobile navigation and horizontally scrollable data tables have no body overflow.
- Power, restart, remote scripts, and bulk commands always require confirmation and disable duplicate submission while pending.
- No deployment smoke test sends commands to classroom PCs unless the operator explicitly selects a safe target.
