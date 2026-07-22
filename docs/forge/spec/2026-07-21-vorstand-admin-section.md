# Feature Design Specification

<!-- One file per feature: docs/forge/spec/<YYYY-MM-DD>-<feature-slug>.md -->

- Feature: Vorstand (Board) admin section
- Date: 2026-07-21
- Review: required

## Goal

The Elternverein needs to record its **Vorstand** (the association's board) and who holds which board role (e.g. Obmann/Obfrau, Kassier, Schriftführer). Today the app models arbitrary parent "Teams" with roles, but the board is special: it is a single, fixed team that admins must not be able to add, remove, or toggle on the ordinary Elterneinteilung (parent-assignment) page — yet a parent's board membership must still be *visible* there. Board membership must be manageable from a dedicated admin page where roles are defined and assigned per parent.

## Requirements

- **R1** A new admin-only page (route `/administration/vorstand`, sidenav entry "Vorstand") exists, reachable only by admins, following the existing `administration` sub-page pattern.
- **R2** The page has two tabs: a **Zuweisung** (assignment) tab and a **Definition** tab (mirroring the tabbed team/role definition UX of the settings/organisation page).
- **R3** In the **Definition** tab, admins can freely add and edit board **roles** (label, optional min/max) exactly like ordinary team roles, but scoped to the board only.
- **R4** In the **Definition** tab, admins can set/edit the board team's **color** (and label), and the change persists.
- **R5** In the **Zuweisung** tab, admins select any parent via a dropdown; once selected, the parent's board roles can be assigned/unassigned. Multiple board roles per parent are allowed.
- **R6** Assigning a parent's first board role automatically adds the board **team** membership to that parent; unassigning a parent's last remaining board role automatically removes the board team membership. This invariant (board team present ⇔ ≥1 board role, counted **only over board roles** — see D6) is enforced server-side in a single request. It is not wrapped in a database transaction (the app uses none — see D3); instead the endpoint is **self-healing**: it recomputes the board-team row from the person's current board-role state on every call, and writes the board-team sync as its last operation, so any interrupted call leaves at most a stale board-team row that the next assignment action corrects.
- **R7** On the Elterneinteilung page, a parent who holds board membership shows the board team and its assigned board role(s) as a **read-only** chip group: the board team chip cannot be toggled on/off and its role chips cannot be selected/deselected.
- **R8** The board team never appears in the ordinary team-management UI (settings/organisation "Parent Teams" tab) as an addable/deletable/toggleable team, and is not offered as a normal assignable team in Elterneinteilung's team chip set.
- **R9** Board assignments are scoped per semester, consistent with all other team/role assignments; the Zuweisung tab has a semester selector.

## Success Criteria

- An admin can define a "Kassier" board role, assign it to parent X for semester S, and X immediately shows board membership + "Kassier" on both the Vorstand page and the Elterneinteilung page for semester S.
- Removing X's only board role for semester S removes X's board team membership for S automatically (verified in the database `semester_assignments` and in both views).
- On Elterneinteilung, clicking the board chip or a board role chip does nothing (no PATCH request fires).
- Changing the board color on the Definition tab is reflected on both pages after reload.
- A non-admin cannot reach `/administration/vorstand` (redirected) and cannot call the board endpoints.

## Existing Architecture

Verified against the code:

- **Teams/roles are not entities.** They are `FieldDefinition` (template, collection `field_definitions`, `fieldName` e.g. `parent-team` / `parent-team-role`) + `FieldInstance` (values, collection `field_instances`), grouped by `Organisation` docs (collection `organisation`) keyed by `tag` (`parent-teams`, `parent-team-roles`). A team instance's `value` = `{label, color}`; a role instance's `value` = `{label, teamInstanceId, min, max}`.
- **CRUD endpoints:** `OrganisationResource` (`/api/v1/organisation`, get-by-tag, resolves `definitionIds`→full defs), `FieldDefinitionResource` (`/api/v1/field-definitions`), `FieldInstanceResource` (`/api/v1/field-instances`, incl. `PUT /{id}` to update a value — this already supports color edits, but no frontend calls it for teams today).
- **Person↔team/role links** live in `semester_assignments` (`SemesterAssignment{personId, semesterId, definitionId, fieldInstanceId, section}`), where `section="team"` surfaces on `PersonDTO.assignedDuty` and `section="role"` on `PersonDTO.assignedRole`. `PersonResource.PATCH /{id}/assigned-duty` and `/{id}/assigned-role` **toggle** these, are semester-scoped (`?semesterId=`), and are definition-agnostic. `resolveSemesterAssignments` maps sections to the DTO regardless of which definition an instance belongs to.
- **Definition management UI:** `frontend/src/app/settings/organisation/organisation.component.ts` — tabbed screen. It lazily creates the `parent-team` / `parent-team-role` `FieldDefinition` on first use, then creates each team/role as a `FieldInstance`. Teams support add + delete only (no edit path). Color chosen via `<input type="color">`, default `#4285f4`.
- **Assignment UI:** `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts` — `mat-table` of parents; team chips built solely from tag `parent-teams`; `toggleTeam`/`toggleRole` call the PATCH endpoints; de-selecting a team with roles prompts a `ConfirmDialogComponent` and cascades role removal from the frontend; `getTeamColor()` reads `team.value.color`.
- **Admin sub-page pattern:** child route under the `administration` parent in `frontend/src/app/app.routes.ts` (guards `[authGuard, adminGuard]` inherited, lazy `loadComponent`); sidenav `<a mat-list-item>` inside the `@if (currentUser.isAdmin)` block in `app.component.html`; `adminGuard` in `core/guards/admin.guard.ts`; `isAdmin` = person has role value `ADMIN`.
- **Backend authz:** `security/SecurityFilter.isAllowed()` returns true for admins immediately; non-admins hit an explicit whitelist. New admin-only endpoints need no extra config.
- **Seeding:** empty-`definitionIds` org docs are seeded per tag via migrations (`ParentTeamsSeedMigration`, `ParentTeamRolesSeedMigration`).
- **Validation:** `JsonSchemaValidatorService` validates each instance `value` against its def `jsonSchema` on create/update.

## Constraints

- Java/Quarkus backend + Angular (standalone components, Angular Material) frontend; MongoDB via Panache/raw driver.
- Must reuse the existing `FieldDefinition`/`FieldInstance`/`Organisation`/`SemesterAssignment` model and endpoints where possible; no new persistence abstraction.
- Route names are German (`elterneinteilung`, `bilanzen`); organisation tags and `fieldName`s are English (`parent-teams`). Follow both conventions.
- UI labels German; code identifiers English ("board" for Vorstand).
- Admin-only; must respect `adminGuard` (frontend) and `SecurityFilter` (backend).

## Design Decisions

**D1: The board is its own organisation tag, not a marked instance inside `parent-teams`.**
- New tags `board` (holds the single board-team `FieldDefinition` `fieldName:"board"`) and `board-roles` (`fieldName:"board-role"`). The board team is a single `FieldInstance` (`value:{label,color}`); board roles are `FieldInstance`s (`value:{label,teamInstanceId,min,max}`) pointing at it.
- Rationale: keeps the board out of the ordinary `parent-teams` list automatically (R8) with no filtering hacks in the settings page; reuses the identical instance shapes and CRUD endpoints. Elterneinteilung must additionally load these tags to display board membership read-only (R7) — an explicit, small load, cleaner than an exclusion predicate on a shared collection.
- Status: assumption (see Assumptions).

**D2: Board membership is semester-scoped, reusing `semester_assignments`.**
- `section="team"` / `section="role"` rows are stored exactly as for parent teams; `PersonDTO.assignedDuty`/`assignedRole` therefore already carry board data with no DTO change.
- Rationale: consistent with the whole app's semester model; zero new resolution logic. Cost: board must be (re)assigned per semester.
- **Distinct-class invariant (G-004):** because board entries flow through the same `assignedDuty`/`assignedRole` arrays as parent teams/roles with no marker of their own, board entries are a **distinct class** that consumers must not blindly aggregate as parent teams. They are identified by membership in the `board`/`board-roles` tag instance sets. Verified today only `elterneinteilung.component.ts` reads these fields, and it keys strictly off the `parent-teams`/`parent-team-roles` instance sets (`this.teams`/`this.roles`), so injected board rows are inert for all existing code and the new board display is purely additive. Any **future** consumer that aggregates over `assignedDuty`/`assignedRole` (counts, dashboards, reports) must exclude board entries via the board tags. This constraint is stated so it is not rediscovered as a bug.
- Status: confirmed by user (semester scope); distinct-class constraint added per G-004.

**D3: The team⇔role invariant (R6) is enforced by a dedicated backend endpoint, in a single self-healing request (no DB transaction).**
- New `PATCH /api/v1/persons/{id}/board-role?semesterId=` (body `{definitionId, fieldInstanceId}`) toggles the given board role and, in the same request, ensures the board-team `semester_assignment` exists iff the person still has ≥1 board role for that semester.
- Rationale: the invariant is a data-integrity rule; enforcing it in one server call avoids the race-prone multi-PATCH frontend orchestration the existing Elterneinteilung cascade uses. The board-team's `definitionId`/`fieldInstanceId` are looked up server-side from the `board` tag so the client cannot desync them.
- **Not atomic in the transactional sense.** The codebase performs semester-assignment writes as independent raw-driver operations with no MongoDB session/transaction (see `PersonResource.toggleAssignment`, `patchGroup`); this design follows that convention rather than introducing transactions (which would require a replica set the deployment does not assume). Correctness under interruption instead rests on two properties: (1) the endpoint **recomputes** board-team membership from the person's current board-role rows on every call — it never blindly increments/decrements — so it is idempotent and self-correcting; (2) the board-team sync is the **last** write, so a crash between the role toggle and the team sync leaves the role state authoritative and at most a stale board-team row, which the next `board-role` call (or a board-role deletion, D7) reconciles. The accepted residual window is a single stale board-team row between a crash and the next admin action, at single-admin scale.
- Status: confirmed by user (semester scope + server-side enforcement); atomicity wording resolved per G-003.

**D6: "Board roles" are identified server-side by membership in the `board-roles` tag, not by section.**
- Board roles and parent-team roles share `section="role"` in `semester_assignments` (D2), so the R6 recompute must not count all `section="role"` rows. The endpoint loads the `board-roles` tag once, resolves its definition(s) → the set of board-role `FieldInstance` ids, and counts only the person's `section="role"` assignments whose `fieldInstanceId` is in that set. Resolving via the tag's live instance set (rather than a single definition id) is robust to multiple or outdated board-role definitions.
- Rationale: removes the ambiguity that a naive "≥1 role" count would introduce (a parent with any parent-team role would otherwise be treated as holding a board role). Mirrors how the board *team* id is resolved server-side from the `board` tag.
- Status: resolved per G-002.

**D7: Deleting a board role cascades server-side to preserve R6.**
- Board-role deletion does **not** go through the invariant-unaware `FieldInstanceResource.DELETE`. A dedicated admin-only endpoint `DELETE /api/v1/persons/board-role/{fieldInstanceId}` (1) finds all `section="role"` assignments referencing that instance across every semester, (2) deletes them, (3) recomputes each affected `(person, semesterId)` board-team row per R6 (removing the board-team row where that instance was the person's last board role for that semester), and (4) deletes the `FieldInstance`. The Definition tab calls this path for board roles.
- Rationale: `FieldInstanceResource.DELETE` hard-deletes the instance and touches no `semester_assignments`, so routing board-role deletion through it would orphan board-team membership (G-001). Concentrating the cascade in one endpoint keeps the invariant server-enforced on every mutation path.
- Status: confirmed by user (cascade over block-until-unassigned).

**D4: The Vorstand page reuses existing definition endpoints directly for role/color management, except board-role deletion.**
- Definition tab uses `FieldInstanceResource` (`POST`/`PUT /field-instances`) and lazy `FieldDefinition` creation, mirroring `organisation.component.ts`. Color/label edit wires the already-existing `PUT /field-instances/{id}` (new frontend usage, no new endpoint) (R4). **Board-role deletion is the one exception**: it uses the cascading `DELETE /api/v1/persons/board-role/{fieldInstanceId}` (D7), not `DELETE /field-instances/{id}`, because only the former preserves the R6 invariant.
- The board-team `FieldDefinition` is created lazily with `jsonSchema` `{"type":"object","properties":{"label":{"type":"string"},"color":{"type":"string"}},"required":["label"]}` — i.e. it validates the exact `{label, color}` value shape the color/label `PUT` sends (mirroring the parent-team def). This makes the R4 edit path pass `JsonSchemaValidatorService.validate`. The board-role `FieldDefinition` mirrors the parent-team-role schema (`{label, teamInstanceId, min?, max?}`).
- Status: assumption (English term / endpoint reuse); board-team schema and deletion path resolved per G-006/G-001.

**D5: English identifier for "Vorstand" is "board".**
- Tags `board`/`board-roles`, `fieldName` `board`/`board-role`, route `vorstand` (German, per route convention), component `VorstandComponent`, UI label "Vorstand".
- Status: assumption.

## Assumptions

- **Assumption (D1):** A separate `board`/`board-roles` tag is preferred over a flag on a `parent-teams` instance, because it keeps the board invisible to the ordinary team UI without shared-collection filtering — flag if the board should instead live inside `parent-teams`.
- **Assumption (D4/D5):** "board" is the right English term and reusing the field-instance endpoints (rather than new board-specific CRUD endpoints) is acceptable — flag if a distinct board API surface is wanted.
- **Assumption:** There is exactly **one** board team instance (singleton). The page creates it lazily on first visit if absent (default label "Vorstand", default color `#4285f4`). Flag if multiple boards are ever needed.
- **Assumption:** Board **roles** reuse `min`/`max` semantics identically to parent-team roles; min/max are optional. Flag if board roles need different constraints (e.g. hard singletons).
- **Assumption:** Elterneinteilung should *display* board membership but never allow editing it; the board is edited only on the Vorstand page. Flag if board should be fully hidden from Elterneinteilung instead.

## Component Responsibilities

- **New `VorstandComponent`** (`frontend/src/app/administration/vorstand/`): owns the two-tab UI. Zuweisung tab: semester selector + parent dropdown + per-parent board-role assignment. Definition tab: board-role add/edit + board color/label edit. Lazily ensures the board definition + singleton instance exist.
- **`PersonResource` (backend):** gains the `board-role` toggle endpoint (self-healing single-request R6 enforcement, D3) and the cascading `DELETE /board-role/{fieldInstanceId}` endpoint (D7). Both resolve board-team / board-role ids server-side from the `board` / `board-roles` tags (D6).
- **`ElterneinteilungComponent`:** gains a read-only board section (loads `board`/`board-roles` tags; renders board team + roles as non-interactive chips for parents who hold them).
- **Existing `FieldInstanceResource` / `FieldDefinitionResource` / `OrganisationResource`:** reused unchanged for board role/color/definition CRUD.
- **Migrations:** seed empty `board` and `board-roles` organisation docs (mirroring the parent-teams seeds).

## Interfaces

New backend endpoints (both admin-only via `SecurityFilter`: auto-allowed for admins, default-denied otherwise — no whitelist entry needed):

```
PATCH /api/v1/persons/{id}/board-role?semesterId={semesterId}
Body: { "definitionId": "<board-role def id>", "fieldInstanceId": "<board role instance id>" }
Behavior (single request, semester-scoped; NOT a DB transaction — see D3):
  0. Resolve the board-role instance set from the `board-roles` tag and the board-team
     instance + its definitionId from the `board` tag (server-side; client cannot supply them).
  1. Toggle the section="role" assignment for (personId, semesterId, fieldInstanceId).
  2. Recompute from current state — count the person's section="role" rows for this
     semester whose fieldInstanceId ∈ board-role instance set (D6):
     - count ≥ 1 and no board-team row exists → insert section="team" for the board-team instance.
     - count = 0 and a board-team row exists → delete it.
     (This recompute is the LAST write, making the call idempotent and self-healing — D3/R6.)
  3. Return updated PersonDTO (assignedDuty/assignedRole reflect the new state).

DELETE /api/v1/persons/board-role/{fieldInstanceId}
Behavior (cascade, all semesters; preserves R6 — see D7):
  1. Delete every section="role" assignment referencing fieldInstanceId (across all semesters).
  2. For each affected (personId, semesterId), recompute board-team membership per step 2 above
     (remove the board-team row where this was the person's last board role for that semester).
  3. Delete the board-role FieldInstance.
  4. Return 204.
```

Reused, unchanged: `GET /organisation/{tag}` (tags `board`, `board-roles`), `GET/POST/PUT /field-instances` and `DELETE /field-instances/{id}` (the latter for board-team edits only — **never** for board roles, which use the cascade endpoint above), `POST/PUT /field-definitions`, `GET /persons`, `GET /persons/{id}/full?semesterId=`, `GET /semesters`.

No `PersonDTO` shape change (board data flows through the existing `assignedDuty`/`assignedRole` fields).

## Data Flow

Assigning a board role (Zuweisung tab):
1. Admin picks semester and a parent from the dropdown; page shows the parent's current board roles (`GET /persons/{id}/full?semesterId=`).
2. Admin toggles a board role → `PATCH /persons/{id}/board-role?semesterId=` with `{definitionId, fieldInstanceId}`.
3. Backend toggles the role row and syncs the board-team row per R6, returns the updated `PersonDTO`.
4. Page refreshes the parent's board state from the response.

Displaying on Elterneinteilung:
1. Page additionally loads `board`/`board-roles` tag defs + their instances.
2. For each parent, `assignedDuty` containing the board-team instance and `assignedRole` containing board roles are rendered as **read-only** chips (no click handlers / disabled).

Definition tab:
1. Ensure `board`/`board-role` defs exist (lazy create + `PUT` org to append def id, as `organisation.component.ts` does). The board-team def is created with the `{label, color}` `jsonSchema` (D4); the board-role def mirrors the parent-team-role schema.
2. Add/edit role → `POST`/`PUT /field-instances`. Edit color/label → `PUT /field-instances/{board-team-instance-id}`.
3. **Delete a board role → `DELETE /api/v1/persons/board-role/{fieldInstanceId}`** (cascade, D7) — not `DELETE /field-instances/{id}`, which would orphan board-team membership.

## Error Handling

- **No semester selected / no semesters exist:** Zuweisung tab disables assignment and shows a hint (mirror Elterneinteilung's semester-gated behavior).
- **Board definition/instance missing:** created lazily; if creation fails, surface the error and keep the tab read-only rather than half-initialized.
- **Toggle race / stale client state:** the backend endpoint is the source of truth and returns the authoritative `PersonDTO`; the client always rehydrates from the response, and the endpoint recomputes the board-team row from current board-role state on every call (D3), so a double-click cannot desync the team⇔role invariant.
- **Interrupted request (crash / exception mid-endpoint):** because the endpoint is not transactional (D3), an interruption can leave a stale board-team row. This is bounded and self-correcting: the board-team sync is the last write, so the role state stays authoritative, and the next `board-role` toggle or `DELETE /board-role` on the affected person reconciles it. Accepted at single-admin scale.
- **Deleting a board role that is still assigned to parents:** handled by the cascading `DELETE /api/v1/persons/board-role/{fieldInstanceId}` (D7), which removes all referencing role assignments across every semester and recomputes each affected parent's board-team membership before deleting the instance — so no orphaned board-team membership can result. (Note: this is distinct from the definition-level `definitionOutdated` soft-delete used for parent-team defs; board-role removal is a hard instance delete via the cascade endpoint.) A `ConfirmDialogComponent` warns before delete when the role is in use, listing affected parents.
- **Invalid `value` (schema):** `JsonSchemaValidatorService` rejects with the existing error path.

## Migration & Compatibility

- **Data migration:** two new seed migrations create empty `board` and `board-roles` organisation docs (idempotent, keyed by a MIGRATION_ID, mirroring `ParentTeamsSeedMigration`). The board `FieldDefinition` and singleton instance are created lazily on first page visit (no migration needed), consistent with how parent-teams defs are created.
- **Backward compatibility:** no change to existing `PersonDTO`, `SemesterAssignment`, parent-teams, or Elterneinteilung toggling. Existing installations without board data simply show an empty Vorstand page until used. No rollback data hazard (new tags are additive).

## Security Considerations

- Both new endpoints are admin-only, auto-enforced by `SecurityFilter` (admins allowed, others denied) — no whitelist entry needed. Frontend route guarded by `adminGuard`.
- The board-team and board-role `definitionId`/`fieldInstanceId` are resolved **server-side** from the `board` / `board-roles` tags, so a client cannot point board membership at an arbitrary instance or cause the invariant to be computed over the wrong role set.
- **Visibility (corrected per G-005):** board *membership* (which parent holds which board role) lives in `semester_assignments` and surfaces only through `GET /persons/{id}/full` (admin default-deny) and `GET /persons/me` (the requester's own record) — so membership is effectively admin-or-self, not public. However, board role/team **definitions** (labels, colors) are readable by any authenticated user via `GET /api/v1/field-instances`, which `SecurityFilter` whitelists for all authenticated users (verified, line 85). This is identical to how parent-team labels/colors are already exposed and is accepted as consistent; it is **not** a membership leak. The earlier "visible only to admins" phrasing applied only to membership.
- No new PII exposure: board membership is parent name + role, same sensitivity class as existing team assignments.

## Performance Considerations

- Volumes are tiny (one board, a handful of roles, tens of parents). The Zuweisung tab loads persons once and per-parent `getFull` on demand, matching Elterneinteilung. No hot path; no budget concerns.
- Elterneinteilung gains two extra small `GET`s (board tag + instances) at load — negligible.

## Observability

- Reuse existing request logging on `PersonResource`. The new endpoint logs the same way as `assigned-duty`/`assigned-role`. No new metrics/alerts warranted at this scale (mark otherwise N/A).

## Alternatives Considered

- **Board as a flagged instance inside `parent-teams`** (rejected, D1): would auto-appear in Elterneinteilung's chips and in the settings "Parent Teams" tab, forcing an exclusion predicate in every place that lists teams (settings page, Elterneinteilung toggles, filters). Separate tag removes all those special cases at the cost of one extra explicit load where display *is* wanted.
- **Frontend orchestration of the R6 invariant** via sequential `assigned-role` + `assigned-duty` toggles (rejected, D3): matches the existing Elterneinteilung cascade but leaves the invariant client-enforced and race-prone; a failed second call would desync team vs. roles with no recovery path. The single-request endpoint is only marginally more backend code and — because it recomputes the board-team row from role state on every call (D3) — is self-healing rather than merely narrowing the window. (It is not a DB transaction; see D3 for the bounded residual window.)
- **Global (non-semester) board membership** (rejected, D2 per user): would need a special global assignment type and bespoke resolution logic, diverging from the app's uniform semester model.
- **New board-specific CRUD endpoints** for roles/color (rejected, D4): the existing `field-instances` endpoints already do exactly this; new endpoints would duplicate them.

## Risks

- **Two places to keep in sync (Vorstand page + Elterneinteilung read-only view).** Mitigation: both read board state from the same tags/DTO fields; only the Vorstand page writes.
- **Lazy singleton creation could race** if two admins open the page simultaneously and both create the board definition/instance. Mitigation: creation is rare and admin-only; if needed, promote board definition/instance creation into the seed migration to eliminate the race (fallback, not required initially).
- **Semester re-assignment burden** (accepted per D2): admins must re-assign the board each new semester. Acceptable given consistency; revisit if it becomes painful.

## Non Goals

- No multi-board support (single board only).
- No editing of board membership from the Elterneinteilung page (display only).
- No change to how ordinary parent teams/roles are defined or assigned.
- No historical board reporting / cross-semester board timeline.
- No backend enforcement of role `max` (unchanged from today — remains client-side, if enforced at all).

## Open Questions

- None deferred. (All decisions resolved; assumptions above are the flag points.)

## Decision Log

- 2026-07-21 — Initial spec created — design interview. Confirmed with user: semester-scoped board membership (D2) and server-side atomic invariant endpoint (D3). D1/D4/D5 recorded as assumptions.

## Changelog

- 2026-07-21 — resolves G-001 — Board-role deletion now cascades server-side via new `DELETE /api/v1/persons/board-role/{fieldInstanceId}` (new D7); no longer routed through the invariant-unaware `DELETE /field-instances/{id}`. (User chose cascade over block-until-unassigned.)
- 2026-07-21 — resolves G-002 — Added D6: R6 counts only board roles, identified by membership in the `board-roles` tag instance set, not all `section="role"` rows. Interface pseudocode updated with the server-side lookup.
- 2026-07-21 — resolves G-003 — Dropped the "atomic" claim (no DB transactions in the codebase); reworded R6/D3/Interfaces to single-request, self-healing (recompute-on-every-call, board-team sync as last write) with a named bounded residual window. Alternatives Considered reconciled.
- 2026-07-21 — resolves G-004 — Added the distinct-class invariant to D2: board entries in `assignedDuty`/`assignedRole` must not be blindly aggregated as parent teams; identified via the board tags. Noted only Elterneinteilung reads these fields today and is unaffected.
- 2026-07-21 — resolves G-005 — Corrected Security Considerations: membership is admin-or-self; board role/team labels+colors are readable by any authenticated user via `GET /field-instances` (as parent-team labels already are), accepted as consistent — not a membership leak.
- 2026-07-21 — resolves G-006 — Pinned the board-team `FieldDefinition` `jsonSchema` to `{label, color}` (D4/Data Flow) so the R4 color/label `PUT` passes validation.
