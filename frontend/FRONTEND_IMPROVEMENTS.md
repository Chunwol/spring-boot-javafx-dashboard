Frontend Improvements — Prioritized

1. High (low-effort, high-impact)
  - Add `frontend/.env.template` and remove `frontend/.env` from repo (done).
  - Keep WebSocket `Authorization` header and ensure backend validates CONNECT header (done).
  - Add token-change hook to restart WS when login/logout occurs (done).
  - Add rotating file logging to help ops (done).

2. Medium
  - Replace any UI-thread blocking `.join()` calls with non-blocking CompletableFuture chains or ensure calls run in background threads. Currently most `.join()` calls are executed from worker threads; verify and refactor if any occur on JavaFX thread.
  - Improve WebSocket retry/backoff: implement exponential backoff with jitter and surface status in statusbar (partial changes applied).
  - Implement optional 'secure token storage' using OS keychain libraries (platform-specific work + dependency).

3. Low (larger effort)
  - Add end-to-end UI smoke tests for core flows (login → dashboard → reconnect). CI will need a headful runner or VNC/Xvfb on Linux.
  - Split large controllers into smaller services for testability (e.g., DashboardController heavy initialization).

Quick patches to follow (PR plan):
- Patch A: Create `frontend/.env.template` and update docs (done).
- Patch B: Small refactor to ensure any remaining `.join()` from UI thread are moved to background tasks.
- Patch C: Add scripts `scripts/scan-secrets.*` and CI job to run them.

If you approve, I'll implement Patch B next by locating any `.join()` executed on JavaFX thread and converting them to async UI updates.
