Frontend Security Audit - quick findings

Scope: frontend/ code and config files

Findings:
- `frontend/.env` contains dev defaults, including `API_BASE_URL=http://localhost:8888`. This is not a secret, but keeping an environment file in the repository can cause accidental leaks or coupling to `backend/.env` (already removed).
- Tests and code contain many `http://localhost:8888` strings (test fixtures). These are fine for tests but not for production defaults.
- No apparent hard-coded passwords or JWT secrets in frontend source. API token handling is transient (kept in memory and `Preferences` for username only).
- WebSocket connection now attaches `Authorization: Bearer <token>` header. Token is stored in memory only (not persisted by default).

Recommendations:
1. Replace `frontend/.env` in the repository with `frontend/.env.template` containing default keys but no values; add `frontend/.env` to `.gitignore` for deployed builds. Document how to create a local `.env` for dev.
2. Consider persisting tokens only with the user's explicit consent and using OS-secure storage (Windows Credential Manager, macOS Keychain, Linux Secret Service). Prefer a `remember me` flag that persists username only, not token (or store refresh tokens securely server-side).
3. Add a small pre-flight check on startup to warn if `API_BASE_URL` uses plain `http` in production distributions; encourage `https`/`wss` defaults.
4. Add CI lint that scans for accidental secrets (regex for `SECRET|PASSWORD|API_KEY|TOKEN|JWT_SECRET`).
5. Keep WebSocket `Authorization` usage (now implemented). Ensure backend validates STOMP CONNECT headers and fails gracefully.

Short-term tasks I did:
- Implemented `FrontendEnvConfig` decoupling from `backend/.env`.
- Added `Authorization` header to STOMP connect and a token-change hook in `ApiClient`.
- Added notes and suggested next steps here.

Next immediate steps I can take:
- Create `frontend/.env.template` and add `frontend/.env` to `.gitignore` (if you want that default removed from repo).
- Implement optional OS keychain integration (platform-specific, requires extra deps).
- Add a CI pre-commit/scan rule to flag sensitive literals.

If you want, I will create `frontend/.env.template` and remove `frontend/.env` from the repo (or add it to `.gitignore`).
