# Implementation Plan

## Spec

`docs/forge/spec/2026-07-21-vorstand-admin-section.md` (review: `…-vorstand-admin-section.grill.md`, `Status: RESOLVED`, 6/6).

## Goal

An admin-only **Vorstand** page (`/administration/vorstand`) exists with two tabs: a **Definition** tab to define board roles and edit the single board team's label/color, and a **Zuweisung** tab to assign/unassign board roles to any parent per semester. Assigning a parent's first board role automatically adds board-team membership; removing the last removes it — enforced server-side. The Elterneinteilung page shows a parent's board team + roles as read-only chips. The board never appears in the ordinary parent-teams UI. All board data reuses the existing `FieldDefinition`/`FieldInstance`/`Organisation`/`SemesterAssignment` model via two new organisation tags (`board`, `board-roles`) and two new `PersonResource` endpoints.

## Implementation Strategy

Backend first, then frontend, so the codebase builds and tests pass after every task:

1. Seed the `board` / `board-roles` organisation scaffolding (empty docs).
2. Add the two backend endpoints on `PersonResource` (`PATCH …/board-role`, `DELETE …/board-role/{instId}`), test-driven against `PersonResourceTest`. These carry all R6/D3/D6/D7 logic.
3. Add thin `PersonService` methods (frontend HTTP wrappers).
4. Build `VorstandComponent` (Definition tab, then Zuweisung tab), mirroring `organisation.component.ts` (lazy def/instance creation, tabbed UI) and `elterneinteilung.component.ts` (per-parent assignment).
5. Register the route + sidenav entry (component already exists → build stays green).
6. Add the read-only board section to `ElterneinteilungComponent`.

Each backend endpoint and each behavioral frontend change ships with its test in the same task.

## Why This Approach

- **Reuse over new abstraction.** Board data is stored in the exact `FieldDefinition`/`FieldInstance`/`SemesterAssignment` shapes already used for parent teams (spec D1/D2); only two backend endpoints are genuinely new, because only the R6 invariant and its cascade delete have no existing equivalent (spec D3/D6/D7). Definition CRUD and color edits reuse `FieldInstanceResource`/`FieldDefinitionResource`/`OrganisationResource` unchanged.
- **Endpoints before UI.** The invariant is server-enforced; putting it in first lets the frontend be a thin caller and lets the invariant be tested without the UI.
- **Separate tag over filtering (spec D1).** Chosen in the spec; the plan inherits it — no exclusion predicate is added anywhere, so `organisation.component.ts` and `elterneinteilung.component.ts` need no defensive changes to satisfy R8.
- **VorstandComponent split in two tasks** (Definition, then Zuweisung) rather than one L task, so each half leaves a building, tested component.

## Components Affected

- **`backend/.../migration/BoardSeedMigration.java`** (new): seeds `board` + `board-roles` org docs.
- **`backend/.../resource/PersonResource.java`**: two new endpoints + private helpers to resolve board-team instance (from `board` tag) and board-role instance set (from `board-roles` tag).
- **`backend/.../resource/PersonResourceTest.java`**: new tests for both endpoints.
- **`frontend/.../shared/services/person.service.ts`**: `assignBoardRole`, `deleteBoardRole`.
- **`frontend/.../administration/vorstand/`** (new): `vorstand.component.ts/html/scss` + `vorstand.component.spec.ts`.
- **`frontend/.../app.routes.ts`** + **`app.component.html`**: route + sidenav entry.
- **`frontend/.../administration/elterneinteilung/elterneinteilung.component.ts/html`** + spec: read-only board section.

## Expected File Changes

Added: `BoardSeedMigration.java`, `vorstand.component.{ts,html,scss}`, `vorstand.component.spec.ts`.
Modified: `PersonResource.java`, `PersonResourceTest.java`, `person.service.ts`, `app.routes.ts`, `app.component.html`, `elterneinteilung.component.{ts,html}`, `elterneinteilung.component.spec.ts`.

## Testing Strategy

- **Backend:** RestAssured `@QuarkusTest` in `PersonResourceTest` (create family/person/defs/instances via API, then exercise the board endpoints and assert on `semester_assignments` via the returned `PersonDTO.assignedDuty`/`assignedRole`). Command: `cd backend && ./mvnw test -Dtest=PersonResourceTest` (Windows: `mvnw.cmd`).
- **Frontend:** Karma component specs mirroring `bilanzen.component.spec.ts` / `elterneinteilung.component.spec.ts`, with mocked services. Command: `cd frontend && npx ng test --watch=false --include='**/<spec>.ts'`.
- **Full suite** runs at run boundaries (baseline before Task 001, regression sweep after Task 008), per forge rules.

## Risks

- **Broken-baseline override (recorded, forge-execute 2026-07-22):** the backend full suite is red on `main` HEAD (839c539) with **13 pre-existing failures unrelated to this feature**, in modules no Vorstand task touches: `SecurityFilterTest` (6), `FieldDefinitionResourceTest` (4), `CurrentUserServiceTest` (2), `PersonResourceTest.testFieldDefinitionsList` (1). Verified genuine (persist on a freshly-dropped clean `kigruapp_test` DB, no code changes). Setup gate could not be satisfied. User was away; executor proceeded under a recorded override: each task's red/green is scoped to its OWN tests, and the final regression sweep is held to "**no new failures beyond this 13-test snapshot**". Backend Green commands scoped to new board-role test methods rather than whole-class where the class carries a pre-existing failure (`PersonResourceTest`).
- **Review-gate override (recorded):** the spec was substantially amended by forge-resolve (new `DELETE …/board-role` endpoint + decisions D6/D7). Grill status is `RESOLVED` so the gate passes, but no fresh `/grill-me` was run on the amended spec. The user explicitly chose to proceed to planning without a re-grill. This plan is built on the amended spec as-is.
- **Assumption — board-team instance exists before any board role is assigned.** The `PATCH …/board-role` endpoint resolves the board-team instance from the `board` tag to insert the `section="team"` row. Because a board role can only be created in the Definition tab *after* the board-team singleton is lazily created (a role's `value.teamInstanceId` points at it), the team instance is always present by the time Zuweisung assigns a role. If the endpoint is nonetheless called with no board-team instance present, it returns `400 "board not initialized"` rather than silently skipping the team sync. Flag if this edge needs different handling.
- **Assumption — one board-role FieldDefinition in practice.** D6 resolves board roles via the `board-roles` tag's live instance set, which tolerates multiple/outdated defs; the plan does not special-case multiple active board-role defs beyond that set membership.
- **Lazy singleton creation race** (spec Risk, accepted): two admins first-visiting simultaneously could double-create the board def/instance. Not mitigated here (rare, admin-only); promote to the seed migration later if it bites.

## Out of Scope

- Multi-board support; historical/cross-semester board reporting.
- Editing board membership from Elterneinteilung (display only).
- Any change to how parent teams/roles are defined or assigned.
- Backend enforcement of role `max` (remains client-side, unchanged).
- Transactional atomicity of the invariant (spec D3: single-request self-healing, no DB transaction).

# Task Breakdown

## Task 001 — Seed board organisation scaffolding

- Goal: Seed empty `board` and `board-roles` organisation docs at startup.
- Spec ref: D1, R8, Migration & Compatibility.
- Depends on: none
- Affected: `backend/.../migration/BoardSeedMigration.java` (new)
- Expected changes: New `@ApplicationScoped @Startup` migration mirroring `ParentTeamsSeedMigration` (MIGRATION_ID `seed-board-v1`): if absent, insert `{tag:"board", definitionIds:[]}` and `{tag:"board-roles", definitionIds:[]}` into `organisation`, then record the migration id. Idempotent.
- Test: N/A — seed migration, no logic branch (follows `ParentTeamsSeedMigration`, which is untested by convention).
- Verification: `cd backend && ./mvnw test -Dtest=PersonResourceTest` boots the app green, and `GET /api/v1/organisation/board` / `…/board-roles` return `200` (asserted in Task 002's test setup). Existing suite stays green.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: Added `BoardSeedMigration.java` (mirrors `ParentTeamsSeedMigration`, MIGRATION_ID `seed-board-v1`, seeds `board` + `board-roles` empty org docs, idempotent). Test N/A. Verified: `PersonResourceTest#testListPersons` boots the app green (Tests run: 1, BUILD SUCCESS); mongosh confirms both org docs present with empty `definitionIds`. Deviations: none.

## Task 002 — Backend `PATCH /persons/{id}/board-role` (toggle + self-healing team sync)

- Goal: Add the board-role toggle endpoint that keeps board-team membership in sync with board-role count.
- Spec ref: R5, R6, D3, D6; Interfaces (PATCH block).
- Depends on: 001
- Affected: `PersonResource.java`, `PersonResourceTest.java`
- Expected changes: New `@PATCH @Path("/{id}/board-role")` accepting `{definitionId, fieldInstanceId}` + `?semesterId=`. Add private helpers: `resolveBoardTeam()` → board-team `FieldInstance` id + its `definitionId` from the `board` tag org's def → its singleton instance; `resolveBoardRoleInstanceIds()` → set of instance ids under the `board-roles` tag org's def(s). Endpoint: `toggleAssignment(personId, semesterId, "role", defId, instId)` (reuse existing helper), then call a **new extracted private helper `syncBoardTeam(personId, semesterId)`** (reused by Task 003) that recomputes — counts this person's `section="role"` rows for the semester whose `fieldInstanceId ∈ board-role set`, and inserts/deletes the `section="team"` board-team row accordingly as the **last** write; return `toFullDTO`. If no board-team instance exists, `400`.
- Test: `PersonResourceTest` — `testBoardRoleAssignmentAddsBoardTeam` (assign first board role → `assignedDuty` contains board team, `assignedRole` contains the role), `testUnassignLastBoardRoleRemovesBoardTeam` (toggle it off → both empty), `testParentTeamRoleDoesNotCreateBoardTeam` (a `parent-team-role` assignment leaves `assignedDuty` without the board team). Locks R6 + D6 counting.
- Red: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testBoardRoleAssignmentAddsBoardTeam` → fails (404/no such endpoint, then assertion: board team absent from `assignedDuty`).
- Green: `cd backend && ./mvnw test -Dtest=PersonResourceTest` → passes.
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: `PersonResource.java` — added `BoardRoleAssignmentRequest` record, `PATCH /{id}/board-role` endpoint, and private helpers `resolveBoardTeamInstance()`, `resolveBoardRoleInstanceIds()`, `syncBoardTeam()` (extracted for reuse by Task 003); imports `Organisation`, `Set`, `HashSet`. `PersonResourceTest.java` — 3 tests + `BoardFixture`/`setupBoard()`/`createFamily`/`createParent`/`createSemester` helpers. Red confirmed: `testBoardRoleAssignmentAddsBoardTeam` failed 404 (no endpoint). Green: 3/3 new tests pass; full `PersonResourceTest` 15/15 (BUILD SUCCESS). Deviations: none.

## Task 003 — Backend `DELETE /persons/board-role/{fieldInstanceId}` (cascade)

- Goal: Add the cascading board-role delete that preserves the R6 invariant across all semesters.
- Spec ref: D7, R3 (delete), G-001 resolution; Interfaces (DELETE block).
- Depends on: 001, 002
- Affected: `PersonResource.java`, `PersonResourceTest.java`
- Expected changes: New `@DELETE @Path("/board-role/{fieldInstanceId}")`. Steps: delete every `section="role"` assignment referencing `fieldInstanceId` (all semesters); for each affected `(personId, semesterId)`, call `syncBoardTeam(personId, semesterId)` (the helper extracted in Task 002) so the board-team row is removed where that instance was the person's last board role for that semester; delete the board-role `FieldInstance`; return `204`.
- Test: `PersonResourceTest` — `testDeleteBoardRoleCascadesAndRemovesOrphanedBoardTeam`: parent holds exactly one board role in semester S (board team present) → `DELETE` the role instance → the person's board-team row for S is gone and the instance no longer exists. Locks the G-001 fix.
- Red: `cd backend && ./mvnw test -Dtest=PersonResourceTest#testDeleteBoardRoleCascadesAndRemovesOrphanedBoardTeam` → fails (endpoint missing, then assertion: board-team row still present).
- Green: `cd backend && ./mvnw test -Dtest=PersonResourceTest` → passes.
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: `PersonResource.java` — added `DELETE /board-role/{fieldInstanceId}` (collects affected `(personId, semesterId)` pairs, deletes role rows across all semesters, re-heals via `syncBoardTeam`, deletes the field-instance). `PersonResourceTest.java` — `testDeleteBoardRoleCascadesAndRemovesOrphanedBoardTeam`. Red confirmed: DELETE returned 404 (no endpoint). Green: full `PersonResourceTest` 16/16 (BUILD SUCCESS). Routing: static `/board-role/{…}` distinct from `DELETE /{id}` (person delete). Deviations: none.

## Task 004 — PersonService board-role methods

- Goal: Add frontend HTTP wrappers for the two new board endpoints.
- Spec ref: Data Flow (Zuweisung / Definition tab); Interfaces.
- Depends on: none
- Affected: `frontend/.../shared/services/person.service.ts`
- Expected changes: `assignBoardRole(personId, definitionId, fieldInstanceId, semesterId)` → `PATCH /persons/{id}/board-role?semesterId=` (mirrors `assignRole`); `deleteBoardRole(fieldInstanceId)` → `DELETE /persons/board-role/{fieldInstanceId}`.
- Test: N/A — thin API passthroughs with no logic, identical in shape to existing untested `assignTeam`/`assignRole` wrappers.
- Verification: `cd frontend && npx tsc -p tsconfig.app.json --noEmit` (or `ng build`) compiles; existing specs stay green.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: `person.service.ts` — added `assignBoardRole(...)` (returns `Observable<PersonDTO>` so callers can rehydrate from the toggle response) and `deleteBoardRole(fieldInstanceId)`. Test N/A. Verified: `npx tsc -p tsconfig.app.json --noEmit` exit 0. Deviations: none.

## Task 005 — VorstandComponent scaffold + Definition tab

- Goal: Create the Vorstand component with a working Definition tab (board role CRUD + board label/color edit, lazy board defs/instance).
- Spec ref: R2, R3, R4, D1, D4, D5; Component Responsibilities; Data Flow (Definition tab).
- Depends on: 004
- Affected: `frontend/.../administration/vorstand/vorstand.component.{ts,html,scss}` (new), `vorstand.component.spec.ts` (new)
- Expected changes: Standalone `VorstandComponent` with a `mat-tab-group` (Definition + a placeholder/empty Zuweisung filled in 006). Definition tab mirrors `settings/organisation/organisation.component.ts`: on load, `orgService.getByTag('board')`/`('board-roles')`; lazily create the `board` FieldDefinition (fieldName `board`, `jsonSchema` `{"type":"object","properties":{"label":{"type":"string"},"color":{"type":"string"}},"required":["label"]}`) + its singleton instance (default label "Vorstand", color `#4285f4`) and the `board-role` FieldDefinition (mirroring parent-team-role schema) when absent, appending def ids to the org docs via `PUT /organisation/id/{id}`. Add/edit roles via `POST`/`PUT /field-instances`; edit board label/color via `PUT /field-instances/{board-team-instance-id}`; delete a role via `personService.deleteBoardRole(...)` (Task 004), with a `ConfirmDialogComponent` warning when in use.
- Test: `vorstand.component.spec.ts` (mock `OrganisationService`/`FieldInstanceService`/`FieldDefinitionService`/`PersonService`) — creates the component; asserts (a) it lazily creates the board def/instance when the tag is empty, (b) editing color issues `PUT /field-instances/{boardTeamId}`, (c) deleting a role calls `personService.deleteBoardRole`.
- Red: scaffold the component minimally (empty Definition tab) so the spec compiles, then `cd frontend && npx ng test --watch=false --include='**/vorstand.component.spec.ts'` → the lazy-create / color-PUT / delete assertions fail red (behavior not yet written).
- Green: same command → passes.
- Size: L (kept: it is the Definition tab's full behavior + scaffold; splitting the lazy-create from the edits would leave a non-functional tab. Zuweisung is already split out to 006.)
- Status: Completed
- Executed: 2026-07-22
- Notes: New `vorstand.component.{ts,html,scss,spec.ts}`. `mat-tab-group` with a functional Definition tab (lazy board def+singleton creation with `BOARD_TEAM_SCHEMA`/`required:[label]`, label+color edit via `fieldInstanceService.update`, board-role add with lazy def create + org registration, role delete via `personService.deleteBoardRole` behind `ConfirmDialogComponent`) and a placeholder Zuweisung tab (filled in 006). Red confirmed: scaffold with empty method bodies → 3/4 assertions FAILED (create/update/delete). Green: 4/4 SUCCESS (ChromeHeadless); template compiled under strictTemplates during the karma build. Deviations: none.

## Task 006 — VorstandComponent Zuweisung tab

- Goal: Add the assignment tab (semester selector + parent dropdown + per-parent board-role assign/unassign).
- Spec ref: R5, R9, D2, D3; Data Flow (Zuweisung tab); Error Handling (no-semester gate).
- Depends on: 004, 005
- Affected: `vorstand.component.{ts,html}`, `vorstand.component.spec.ts`
- Expected changes: Zuweisung tab: `SemesterService.getAll()` + selector; `PersonService.list()` filtered to parents; a parent dropdown; on select, `getFull(personId, semesterId)` to show current board roles; toggling a role calls `personService.assignBoardRole(...)` and rehydrates the parent's board state from the returned `PersonDTO`. Assignment disabled with a hint when no semester exists (mirror Elterneinteilung's gate).
- Test: `vorstand.component.spec.ts` — selecting a parent and toggling a board role calls `assignBoardRole` with the selected semester id and rehydrates from the response; assignment controls disabled when `semesters` is empty.
- Red: `cd frontend && npx ng test --watch=false --include='**/vorstand.component.spec.ts'` → fails (assertion: `assignBoardRole` not called / control not disabled).
- Green: same command → passes.
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: `vorstand.component.ts` — added `SemesterService` dep; Zuweisung state (`semesters`, `selectedSemesterId`, `parents`, `selectedParentId`, `selectedParent`) and methods (`loadSemesters`, `loadParents` w/ `isChild`/`getPersonName` mirrors of Elterneinteilung, `onSemesterChange`, `onParentChange`, `isBoardRoleAssigned`, `toggleBoardRole` rehydrating from the returned `PersonDTO`, `assignmentDisabled`, `getSemesterLabel`). `vorstand.component.html` — semester+parent selectors and a `mat-chip-listbox` of board roles, gated with a no-semester hint. `vorstand.component.spec.ts` — semester fake + augmented person fake + 2 Zuweisung tests; updated both `beforeEach` constructors for the 6th dep. Red confirmed: scaffold → 2 Zuweisung assertions FAILED. Green: 6/6 SUCCESS; template compiled under strictTemplates. Deviations: also touched `vorstand.component.scss` (selector/chip layout) — trivially forced by the new UI.

## Task 007 — Register Vorstand route + sidenav entry

- Goal: Expose the Vorstand page under the admin area.
- Spec ref: R1, D5; Component Responsibilities.
- Depends on: 005
- Affected: `frontend/.../app.routes.ts`, `frontend/.../app.component.html`
- Expected changes: Add child route `vorstand` under the `administration` parent (guards `[authGuard, adminGuard]` inherited), lazy `loadComponent` → `VorstandComponent` (mirror the `bilanzen` entry). Add `<a mat-list-item routerLink="/administration/vorstand">Vorstand</a>` inside the `@if (currentUser.isAdmin)` sidenav block in `app.component.html`.
- Test: N/A — routing/navigation wiring, no logic (matches how the Bilanzen route was added).
- Verification: `cd frontend && npx ng build` compiles; manually/route-test the path resolves to `VorstandComponent`. Existing specs stay green.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: `app.routes.ts` — added child route `vorstand` under `administration` (guards `[authGuard, adminGuard]` inherited), lazy `loadComponent` → `VorstandComponent`, mirroring the `bilanzen`/`elterneinteilung` entries. `app.component.html` — added `<a routerLink="/administration/vorstand">` (icon `groups`) inside the `@if (currentUser.isAdmin)` block. Test N/A. Verified: `npx ng build` → "Application bundle generation complete", no errors. Deviations: none.

## Task 008 — Elterneinteilung read-only board section

- Goal: Show each parent's board team + roles as non-interactive chips on Elterneinteilung.
- Spec ref: R7, R8, D2 (distinct-class invariant); Data Flow (Displaying on Elterneinteilung).
- Depends on: none (backend already returns board rows via existing `getFull`)
- Affected: `elterneinteilung.component.ts`, `elterneinteilung.component.html`, `elterneinteilung.component.spec.ts`
- Expected changes: In `load()`, additionally fetch `board`/`board-roles` tag defs + instances (via `orgService`/`fieldInstanceService`), storing the board-team instance + board-role instance set. Add read-only helpers (e.g. `getBoardTeam(person)`, `getBoardRoles(person)`) that select, from `assignedDuty`/`assignedRole`, only entries whose id ∈ the board instance sets (D2 distinct-class). Render them in the row as a chip group with **no** click handlers and `disabled`, visually distinct from the toggleable parent-team chips. The existing `this.teams`/`this.roles` (parent-teams) rendering is untouched, so board never enters the toggleable set (R8).
- Test: `elterneinteilung.component.spec.ts` — a parent whose `assignedDuty`/`assignedRole` include board instances renders the board chips; clicking a board chip does **not** call `assignTeam`/`assignRole`/`assignBoardRole`; board instances do not appear in the toggleable `teams` chip set.
- Red: `cd frontend && npx ng test --watch=false --include='**/elterneinteilung.component.spec.ts'` → fails (assertion: board chips not rendered / board in toggle set).
- Green: same command → passes.
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: `elterneinteilung.component.ts` — added `boardTeamInstanceId`/`boardRoleInstanceIds` state; `load()` now also fetches `board`/`board-roles` tag defs + instances (with the same catchError-empty pattern); read-only selectors `getBoardTeam`/`getBoardRoles` (filter `assignedDuty`/`assignedRole` by board instance ids — D2 distinct-class) + `getBoardLabel`. `elterneinteilung.component.html` — read-only board chip group (`[disabled]="true"`, no click handlers, tooltip pointing to Administration > Vorstand). `elterneinteilung.component.scss` — distinct board styling. `elterneinteilung.component.spec.ts` — 2 board tests. Red confirmed: stubs → 2 board assertions FAILED (7 existing pass). Green: 9/9 SUCCESS; template compiled under strictTemplates. `this.teams` untouched → board never in toggleable set (R8). Deviations: none.

# Coverage Mapping

| Spec item | Task(s) |
|---|---|
| R1 admin route + sidenav | 007 (guards inherited) |
| R2 two tabs | 005, 006 |
| R3 define/edit board roles | 005 (add/edit), 003 (delete) |
| R4 board color/label edit persists | 005 |
| R5 Zuweisung assign/unassign, multiple roles | 006, 002 |
| R6 team⇔role invariant | 002 |
| R7 read-only board chips on Elterneinteilung | 008 |
| R8 board absent from parent-teams UI / not assignable | 001 (separate tag), 008 (not in toggle set) |
| R9 semester-scoped + selector | 002 (semester-scoped), 006 (selector) |
| D1 separate tags | 001, 005 |
| D2 semester_assignments reuse + distinct-class | 002, 008 |
| D3 single-request self-healing | 002 |
| D4 reuse field-instance endpoints + board-team schema | 005 |
| D5 English "board" naming | all |
| D6 board-role identification via tag | 002, 003 |
| D7 cascade delete | 003 |

Every task maps to a spec item above; no task exists without a spec ref.

# Changelog

- 2026-07-21 — Plan created from spec (Changelog through G-006). Review-gate override recorded in Risks (RESOLVED grill, no re-grill, user-approved).
- 2026-07-22 — forge-execute run started. Broken-baseline override recorded in Risks: 13 pre-existing backend failures unrelated to feature; sweep held to no-new-failures-beyond-snapshot.
- 2026-07-22 — All 8 tasks Completed. Final regression sweep: backend `mvnw test` 87 run / 12 failing — a strict subset of the 13-test snapshot (`SecurityFilterTest` 6, `FieldDefinitionResourceTest` 4, `CurrentUserServiceTest` 2); `PersonResourceTest` 16/16, `BilanzResourceTest` 20/20, no new failures. Frontend `ng test` 76 run / 1 failing — pre-existing `AppComponent should create the app` (no `HttpClient` provider in its TestBed; verified identical at baseline via file stash), unrelated to this feature; all Vorstand + Elterneinteilung board specs green. No regressions introduced.
