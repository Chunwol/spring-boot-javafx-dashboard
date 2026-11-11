# Device Notes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task by task. Follow `test-driven-development` for every behavior change and `verification-before-completion` before claiming completion.

**Goal:** Add an independent device memo to classroom PCs so an operator can record hardware issues such as the intermittent HP BIOS 90E warning on `218-35` without marking the PC broken or preventing commands, file rollout, remote control, or SCMATE work.

**Architecture:** Add nullable `device_note` persistence to `pc_master`, expose it through the existing PC status DTO, and add a narrowly authorized update endpoint. The React application will show the note in both the PC management table and classroom detail drawer, while editing remains in PC management for users with the existing `BROKEN_PC_REGISTER_EDIT` permission. The legacy `brokenNote` and BROKEN workflow remain unchanged.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA, Flyway/PostgreSQL, SQLite test migrations, React 19, TypeScript, TanStack Query, Vitest/Testing Library, Gradle, GitHub Actions, Docker Compose on the classroom NAS.

**Spec:** Bounded design approved in the 2026-11-11 conversation: a usable-but-needs-inspection PC keeps its live status and all operating capabilities; device notes are independent from fault registration.

## Global Constraints

- Do not repurpose `brokenNote`, change BROKEN status semantics, or modify command eligibility.
- Do not reboot, shut down, or send agent/SCMATE commands as part of this feature.
- Device notes are trimmed, nullable, and limited to 500 characters. An empty/whitespace-only submission clears the note.
- Read access follows the existing PC/classroom view permissions. Write access reuses `BROKEN_PC_REGISTER_EDIT` to avoid adding a new production role-permission migration.
- The note endpoint identifies a PC only by its existing MAC primary key and must URL-encode the MAC on the client.
- PostgreSQL is the production database; SQLite changes exist only to keep automated tests representative.
- The production migration is additive. A rollback returns to the previous application image but deliberately leaves `device_note` in place so recorded data is not destroyed.
- Never store a serial number, credential, or raw diagnostic dump in the device note.

## User-visible behavior

- `PC 관리` gets a `장치 메모` column with a short preview, full text in a title/accessible label, and `메모 없음` when empty.
- An authorized operator can open a focused memo dialog, edit up to 500 characters, save, or clear the note.
- A saved memo refreshes the normal `['pcs']` query and shows a success toast; server errors keep the dialog open and show an error.
- The classroom detail drawer shows the memo read-only beneath the PC identity so staff can notice it during room monitoring.
- A PC with only `deviceNote` remains selectable and keeps all actions that it had before. Only `brokenNote`/BROKEN behavior continues to suppress applicable actions.

## API contract

`PUT /api/v1/dashboard/pcs/{macAddress}/note`

Request:

```json
{ "note": "사용 가능·점검 필요 — HP BIOS 90E 경고가 간헐적으로 발생" }
```

Clear request:

```json
{ "note": null }
```

Response: the updated `PcStatusDto`, including `deviceNote`. Missing PCs use the project's existing `IllegalArgumentException` error mapping. Notes over 500 characters return the existing validation-style 400 response and do not persist a partial update.

---

### Task 1: Persist and transport an independent device note

**Files:**

- Create: `backend/src/main/resources/db/pgmigration/V21__add_device_note.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V43__add_device_note.sql`
- Modify: `backend/src/main/java/com/example/backend/model/pc/PcMaster.java`
- Modify: `common/src/main/java/com/example/dto/dashboard/PcStatusDto.java`
- Modify: `backend/src/main/java/com/example/backend/service/dashboard/PcStatusService.java`
- Test: `backend/src/test/java/com/example/backend/service/dashboard/PcStatusServiceTest.java`

**Step 1: Write failing service tests**

Add tests proving that `updateDeviceNote` trims and saves without changing `status` or `brokenNote`; whitespace clears to `null`; 501 characters are rejected before save and entity mutation; DTO conversion returns `deviceNote`.

Run:

```powershell
.\gradlew.bat :backend:test --tests "com.example.backend.service.dashboard.PcStatusServiceTest" -PskipWebFrontendBuild
```

Expected: FAIL because the field and method do not exist.

**Step 2: Add migrations and model fields**

- Both migrations execute `ALTER TABLE pc_master ADD COLUMN device_note VARCHAR(500);`.
- Add `@Column(name = "device_note", length = 500) private String deviceNote;` to `PcMaster`.
- Add nullable `String deviceNote` to `PcStatusDto`.

**Step 3: Add normalization and update behavior**

Add `DEVICE_NOTE_MAX_LENGTH = 500`, `updateDeviceNote(String macAddress, String note)`, and DTO mapping. Validate the trimmed value before mutating the entity.

**Step 4: Re-run the targeted service test**

Expected: PASS.

**Step 5: Commit**

```powershell
git add backend/src/main/resources/db/pgmigration/V21__add_device_note.sql backend/src/main/resources/db/migration/sqlite/V43__add_device_note.sql backend/src/main/java/com/example/backend/model/pc/PcMaster.java common/src/main/java/com/example/dto/dashboard/PcStatusDto.java backend/src/main/java/com/example/backend/service/dashboard/PcStatusService.java backend/src/test/java/com/example/backend/service/dashboard/PcStatusServiceTest.java
git commit -m "feat(pcs): persist independent device notes"
```

### Task 2: Add the secured device-note API

**Files:**

- Modify: `backend/src/main/java/com/example/backend/controller/dashboard/DashboardController.java`
- Modify: `backend/src/main/java/com/example/backend/security/FeaturePermissionAuthorizationFilter.java`
- Test: `backend/src/test/java/com/example/backend/security/FeaturePermissionAuthorizationFilterTest.java`
- Create: `backend/src/test/java/com/example/backend/controller/dashboard/DashboardControllerDeviceNoteTest.java`

**Step 1: Write failing authorization tests**

Prove that `PERM_BROKEN_PC_REGISTER_EDIT` may call the note PUT endpoint and a user with only `PERM_PC_VIEW` receives 403.

Run:

```powershell
.\gradlew.bat :backend:test --tests "com.example.backend.security.FeaturePermissionAuthorizationFilterTest" -PskipWebFrontendBuild
```

Expected: FAIL because the generic PC route currently treats the PUT as a read-style route.

**Step 2: Add route-specific authorization**

Match `PUT /api/v1/dashboard/pcs/[^/]+/note` before the generic PC rule and require `BROKEN_PC_REGISTER_EDIT`.

**Step 3: Write a failing controller test**

Use a focused controller test to prove that the decoded MAC and nullable `note` reach `PcStatusService.updateDeviceNote` and the updated DTO is returned.

**Step 4: Add the endpoint**

Add `@PutMapping("/pcs/{macAddress}/note")`, OpenAPI descriptions, and a small typed request record/class rather than an unstructured map.

**Step 5: Run targeted API and authorization tests**

```powershell
.\gradlew.bat :backend:test --tests "com.example.backend.security.FeaturePermissionAuthorizationFilterTest" --tests "com.example.backend.controller.dashboard.DashboardControllerDeviceNoteTest" -PskipWebFrontendBuild
```

Expected: PASS.

**Step 6: Commit**

```powershell
git add backend/src/main/java/com/example/backend/controller/dashboard/DashboardController.java backend/src/main/java/com/example/backend/security/FeaturePermissionAuthorizationFilter.java backend/src/test/java/com/example/backend/security/FeaturePermissionAuthorizationFilterTest.java backend/src/test/java/com/example/backend/controller/dashboard/DashboardControllerDeviceNoteTest.java
git commit -m "feat(pcs): expose secured device note API"
```

### Task 3: Add the web editing workflow without changing operations

**Files:**

- Modify: `web-frontend/src/types/dashboard.ts`
- Modify: `web-frontend/src/features/pcs/api.ts`
- Create: `web-frontend/src/features/pcs/DeviceNoteDialog.tsx`
- Modify: `web-frontend/src/features/pcs/pcs.css`
- Modify: `web-frontend/src/pages/PcsPage.tsx`
- Modify: `web-frontend/src/pages/PcsPage.test.tsx`

**Step 1: Write failing page tests**

Extend the API mock with `updateDeviceNote` and prove that an existing note is visible; an authorized user can save a trimmed note for the correct MAC; clearing sends `null`; a note-bearing ONLINE PC retains its operation and remote-control buttons; a view-only user can read but not edit.

Run from `web-frontend`:

```powershell
npm test -- --run src/pages/PcsPage.test.tsx
```

Expected: FAIL because the type, client call, column, and dialog do not exist.

**Step 2: Add the client contract and dialog**

Add `deviceNote?: string | null`, add `updateDeviceNote(macAddress, note)` using `encodeURIComponent`, and implement `DeviceNoteDialog` with shared focus/dialog patterns, a 500-character textarea, character count, save, clear, cancel, busy state, and accessible labels.

**Step 3: Integrate PC management**

Gate editing with `BROKEN_PC_REGISTER_EDIT`; add the column and edit action; invalidate `['pcs']` on success. Do not reference `deviceNote` in selection, rollout, command, or operations predicates.

**Step 4: Run targeted tests and type checking**

```powershell
npm test -- --run src/pages/PcsPage.test.tsx
npm run typecheck
```

Expected: PASS.

**Step 5: Commit**

```powershell
git add web-frontend/src/types/dashboard.ts web-frontend/src/features/pcs/api.ts web-frontend/src/features/pcs/DeviceNoteDialog.tsx web-frontend/src/features/pcs/pcs.css web-frontend/src/pages/PcsPage.tsx web-frontend/src/pages/PcsPage.test.tsx
git commit -m "feat(web): edit device notes from PC management"
```

### Task 4: Show device notes in classroom monitoring

**Files:**

- Modify: `web-frontend/src/pages/ClassroomsPage.tsx`
- Modify: `web-frontend/src/styles/index.css`
- Modify: `web-frontend/src/pages/ClassroomsPage.test.tsx`

**Step 1: Write a failing classroom drawer test**

Return a PC with `deviceNote`, open the classroom drawer, and assert that the note is visible while the PC still counts as ONLINE rather than broken.

Run:

```powershell
npm test -- --run src/pages/ClassroomsPage.test.tsx
```

Expected: FAIL because the drawer does not render the note.

**Step 2: Render the read-only memo**

Add a secondary memo line/badge only when `deviceNote` is non-empty. Keep status markers and counts based on existing status/broken fields.

**Step 3: Run targeted tests and type checking**

```powershell
npm test -- --run src/pages/ClassroomsPage.test.tsx
npm run typecheck
```

Expected: PASS.

**Step 4: Commit**

```powershell
git add web-frontend/src/pages/ClassroomsPage.tsx web-frontend/src/styles/index.css web-frontend/src/pages/ClassroomsPage.test.tsx
git commit -m "feat(classrooms): surface device notes in room details"
```

### Task 5: Full verification and regression checks

**Step 1: Run backend suites**

```powershell
.\gradlew.bat :backend:test -PskipWebFrontendBuild
.\gradlew.bat :backend:integrationTest -PskipWebFrontendBuild
```

Expected: PASS, including Flyway startup against the SQLite test profile.

**Step 2: Run all frontend checks**

From `web-frontend`:

```powershell
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

**Step 3: Build the production artifact**

```powershell
.\gradlew.bat :backend:clean :backend:bootJar --no-daemon
```

Expected: PASS and `backend/build/libs/backend-0.0.1-SNAPSHOT.jar` contains the new web frontend.

**Step 4: Review the final diff**

```powershell
git status --short
git diff --check
git diff origin/main...HEAD --stat
```

Confirm no production secret, unrelated classroom action, status predicate change, or generated output is included.

### Task 6: Production-safe rollout and 218-35 registration

**Step 1: Merge through the reviewed path**

Open the feature branch for review and merge to `main` only after Task 5 passes. The existing `deploy-production` workflow builds the backend with the embedded frontend and deploys on the NAS runner.

**Step 2: Observe deployment safeguards**

Verify in workflow output that the current JAR/image and a non-empty PostgreSQL dump were backed up, only the app container changed, Flyway applied `V21`, and the app reached healthy. Do not report deployment complete from a push or accepted job alone.

**Step 3: Live smoke test before real data**

Load PC management with an editor account; confirm the column renders; save and clear a temporary note on a non-critical test record; confirm operation buttons remain available; confirm a view-only account can read but not edit.

**Step 4: Register the real 218-35 note**

After confirming the current MAC from the live dashboard/inventory, save:

```text
사용 가능·점검 필요 — HP BIOS 90E 전원부 팬 미감지 오류가 간헐적으로 발생. 현재 팬 1194RPM/온도 정상. HWM Onsite Care Pack 2028-01-30까지 활성, 보증수리 예정.
```

Do not set BROKEN status and do not add its serial number.

**Step 5: Verify live behavior**

Confirm the note appears in PC management and the 218 classroom drawer; `218-35` remains ONLINE when heartbeat is online; its command/remote/SCMATE actions are unchanged; the note persists after refresh.

**Step 6: Rollback rule**

On health failure, the existing script restores the prior JAR/image. For a post-deploy regression, redeploy the rollback image/JAR. Leave the additive `device_note` column in PostgreSQL: the previous app ignores it and the data remains available for a corrected redeploy. Restore the DB dump only for demonstrated data corruption, not for a normal application rollback.

**Step 7: Record verified completion**

Create a dated KnowledgeHub session note and update the `교실 PC 관리` brief with the deployed commit, workflow and migration evidence, live `218-35` verification, any rollback, and next maintenance step. Do not record secrets or raw database dumps.

## Review Focus

1. Confirm reusing `BROKEN_PC_REGISTER_EDIT` for device-note editing.
2. Confirm editing only in `PC 관리`, with read-only display in the classroom drawer.
3. Confirm the 500-character limit and exact `218-35` note.
4. Confirm production rollout uses the reviewed merge and guarded GitHub Actions path, not an ad-hoc NAS edit.
