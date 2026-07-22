# Grill Review — Vorstand (Board) admin section

- Target: `docs/forge/spec/2026-07-21-vorstand-admin-section.md`
- Date: 2026-07-21
- Reviewer note: This spec was authored in a prior session by this same agent identity. This review ran in a fresh post-`/clear` session with no active author reasoning, but the reader should weight findings knowing residual author bias is possible. Re-run from a clean subagent if independence matters.

Scope: verified the spec's "Existing Architecture" claims against `PersonResource.java`, `SecurityFilter.java`, `FieldInstanceResource.java`, and `elterneinteilung.component.ts`. The architecture description is largely accurate (semester_assignments shape, toggle endpoints, section="team"/"role", admin default-deny, `PUT /field-instances/{id}` exists). Findings below concern the *design*, not the architecture summary.

---

### [G-001] [Blocker] Reused `DELETE /field-instances/{id}` bypasses the R6 invariant and orphans board-team membership
- **Section:** D3/D4, R6, Component Responsibilities, Error Handling, Interfaces
- **Finding:** R6 asserts the invariant "board team present ⇔ ≥1 board role" is "enforced server-side and atomically." But D4 and Component Responsibilities route all board-role definition management (including deletion) through the **unchanged** `FieldInstanceResource.DELETE /field-instances/{id}`. That endpoint (verified, lines 142–152) hard-deletes the instance document and knows nothing about `semester_assignments`. Concrete failure: parent X holds exactly one board role "Kassier" for semester S (so X has a `section="team"` board-team assignment). An admin deletes the "Kassier" role instance in the Definition tab. The `section="role"` assignment row referencing that instance is now orphaned — `resolveRefs` silently skips missing instances (PersonResource line 364), so X's `assignedRole` drops it — but the `section="team"` board-team row is never recomputed and remains. X now shows board membership with zero board roles: the invariant R6 promised is violated through a path the design explicitly endorses. The Error Handling section compounds this by claiming deletion is covered by "existing `definitionOutdated` handling" — but `definitionOutdated` is a *definition*-level `outdatedAt` flag (FieldInstanceResource line 201), not an instance delete; a hard instance DELETE has no outdated semantics at all.
- **Question the author must answer:** When a board-role FieldInstance is deleted while still assigned to parents for some semester, what recomputes the board-team invariant for every affected (person, semester) pair — and if the answer is "the board-role toggle endpoint," why is deletion routed through the invariant-unaware `FieldInstanceResource.DELETE` instead?
- **Resolution:** Resolved: new spec D7 + `DELETE /api/v1/persons/board-role/{fieldInstanceId}` cascade endpoint deletes all referencing role assignments across semesters, recomputes each affected board-team row, then deletes the instance; Definition tab uses this path, never `FieldInstanceResource.DELETE`, for board roles. (User chose cascade over block-until-unassigned.)

### [G-002] [Major] The R6 counting step cannot distinguish board roles from parent-team roles as specified
- **Section:** Interfaces (behavior step 2), R6, D2
- **Finding:** Board roles are stored as `section="role"` rows in `semester_assignments`, the **same** section value as parent-team roles (D2: "stored exactly as for parent teams"). The endpoint's step 2 says "Recompute: does the person still hold ≥1 board role for this semester?" — but the described data gives no field that separates a board role from a parent-team role except which `definitionId`/`fieldInstanceId` it points at. The Interfaces pseudocode never states that the recompute must filter `section="role"` rows down to board-role instances (e.g. by matching `definitionId` against the lazily-created `board-role` def, or membership in the `board-roles` tag). A literal implementation counting all `section="role"` rows would keep the board-team assignment for any parent who holds *any* parent-team role but no board role — silently wrong. The spec resolves the analogous lookup for the board *team* ("resolved server-side from the `board` tag") but is silent for board *roles*.
- **Question the author must answer:** Exactly which server-side lookup identifies "board roles" when counting — the `board-role` FieldDefinition id, the `board-roles` tag's instance set, or something else — and how does it handle multiple/outdated board-role definitions?
- **Resolution:** Resolved: new spec D6 — count only `section="role"` rows whose `fieldInstanceId` ∈ the `board-roles` tag's live instance set (resolved server-side each call), robust to multiple/outdated board-role definitions. Interface pseudocode step 0/2 updated.

### [G-003] [Major] "Atomic" is overclaimed — the endpoint is a sequence of non-transactional Mongo ops
- **Section:** R6, D3, Interfaces ("atomic, semester-scoped"), Error Handling, Alternatives Considered
- **Finding:** The design sells the new endpoint's key advantage over frontend orchestration as atomicity (D3, Alternatives: "removes the failure mode"). But the existing data layer uses the raw driver with independent `countDocuments` / `deleteMany` / `insertOne` calls and no client session or multi-document transaction (verified in `toggleAssignment`, lines 77–94, and `patchGroup`). The board-role endpoint performs at least two logically separate writes (toggle the role row, then insert/delete the team row). If the process dies or an exception is thrown between them, the invariant is left broken exactly like the rejected frontend-orchestration case — the window is narrower (one request), not eliminated. The word "atomically" in R6 and the interface is a correctness claim the design does not actually deliver, and the whole justification for D3 over the alternative rests on it.
- **Question the author must answer:** Is the endpoint backed by a real MongoDB multi-document transaction, or is "atomic" meant only as "single request, smaller window"? If the latter, R6/D3 should be reworded and the residual partial-failure state (role without team, or team without role) explicitly named as accepted.
- **Resolution:** Resolved: no transaction (codebase uses none / no replica-set assumption). Reworded R6/D3/Interfaces/Alternatives to single-request + self-healing (recompute board-team from role state every call; board-team sync as last write). Residual window (one stale board-team row until next admin action) named and accepted at single-admin scale in D3 + Error Handling.

### [G-004] [Minor] Routing board data through shared `assignedDuty`/`assignedRole` is safe today but silently couples future aggregates
- **Section:** D2, Interfaces ("No PersonDTO shape change"), Data Flow
- **Finding:** Verified that `assignedDuty`/`assignedRole` are consumed only by `elterneinteilung.component.ts` (frontend) and produced only by `toFullDTO` (backend), and that Elterneinteilung's existing methods key off `this.teams`/`this.roles` (the `parent-teams`/`parent-team-roles` instance sets), so board rows injected into these arrays are inert for existing code — no regression. The finding is forward-looking: the spec presents "no DTO change" as pure upside, but it means every current and future reader of `assignedDuty`/`assignedRole` now receives board rows commingled with parent-team rows with no marker to tell them apart. Any later aggregate ("how many teams does this parent have", a dashboard, a report) will silently over-count. The new Elterneinteilung read-only section must also positively identify board entries by cross-referencing the `board`/`board-roles` instance ids loaded separately — which the Data Flow assumes but does not make a hard requirement.
- **Question the author must answer:** Should the constraint "consumers of `assignedDuty`/`assignedRole` must treat board entries as a distinct class, identified via the board tags" be written into the spec as an explicit invariant, so future features don't miscount?
- **Resolution:** Resolved: distinct-class invariant added to D2, with the verified note that only `elterneinteilung.component.ts` reads these fields today and is unaffected (keys off `parent-teams`/`parent-team-roles` sets).

### [G-005] [Minor] "Visible only to admins" contradicts world-readable `field-instances` GET
- **Section:** Security Considerations
- **Finding:** The spec states board membership is "visible only to admins." Verified `SecurityFilter.isAllowed` whitelists `GET /api/v1/field-instances` for *any* authenticated non-admin (line 85). Board role/team definitions (labels, colors) are therefore readable by every logged-in parent, not just admins. Actual board *membership* (person↔role links in `semester_assignments`) is not exposed this way — it only surfaces through `/persons/{id}/full` (admin-only) or `/persons/me` (self) — so the leak is limited to board role/team labels and colors, not who holds them. Still, the security claim as written is inaccurate.
- **Question the author must answer:** Is exposure of board role/team labels and colors to all authenticated users acceptable, and should the Security section be corrected to say membership (not definitions) is admin-only?
- **Resolution:** Resolved: Security Considerations corrected — membership is admin-or-self; labels/colors are world-readable to authenticated users via `GET /field-instances` (line 85), identical to existing parent-team exposure, accepted as consistent and not a membership leak.

### [G-006] [Minor] R4 color edit depends on an unspecified lazily-created board-team `jsonSchema`
- **Section:** D4, R4, Data Flow (Definition tab), Assumptions
- **Finding:** `FieldInstanceResource.PUT /{id}` validates the incoming `value` against the definition's `jsonSchema` (verified, lines 123–129) and returns 400 on mismatch. The board-team def and its `jsonSchema` are created lazily by the new Vorstand page ("mirroring organisation.component.ts"). If that lazily-created schema does not permit exactly the `{label, color}` shape (e.g. it omits `color`, or forbids additional properties), the R4 color/label edit silently fails validation at runtime with no design-time signal. The spec treats "reuse `PUT /field-instances/{id}`" as free, but its correctness hinges entirely on a schema the spec never pins down.
- **Question the author must answer:** What is the exact `jsonSchema` the board-team FieldDefinition is created with, and does it validate `{label, color}` for the color-edit PUT path?
- **Resolution:** Resolved: spec now pins the board-team `jsonSchema` to `{"type":"object","properties":{"label":{"type":"string"},"color":{"type":"string"}},"required":["label"]}` in D4 + Data Flow, so the R4 color/label PUT validates.

---

## Silent attack vectors (checked, nothing material)
- **Scale/limits:** Genuinely tiny (one board, handful of roles, tens of parents). No concern — agree with the spec.
- **Migration/coexistence:** New additive tags; no backfill; empty Vorstand page pre-use. Clean. Lazy multi-step creation partial-failure is acknowledged as a Risk.
- **Frontend blast radius:** Confirmed only Elterneinteilung reads the shared DTO fields; existing methods are keyed to parent-teams instances and ignore board rows (see G-004 for the forward-looking caveat).
- **Success criteria:** Measurable and verifiable (DB collection named correctly, "no PATCH fires" is observable). Good.
- **Authz on the new endpoint:** Admin default-deny confirmed; `/persons/{id}/board-role` needs no whitelist entry, as claimed.

## Verdict
- Verdict: REVISE
- Resolved: 6 / 6
- Status: RESOLVED
