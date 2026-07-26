# Grill Review — Mail Templates & Jobs

- Target: `docs/forge/spec/2026-07-22-mail-templates-jobs.md`
- Reviewer: adversarial (fresh, non-author)
- Date: 2026-07-22

Scope note: claims in §Existing Architecture were spot-checked against the repo and are largely accurate (MailService signature, MailSettings singleton, SecurityFilter default-deny, semester_assignments join, absence of quarkus-scheduler, Quarkus 3.36.1). Findings below are where the *design* rests on a wrong or missing detail, not where the architecture description is wrong.

---

### [G-001] [Blocker] Group recipient selection stores an id the reused endpoint never returns
- **Section:** R15, D5, Interfaces (Groups reused), Component Responsibilities (Recipient resolver), Data Flow step 3
- **Finding:** The job persists `recipientGroupInstanceIds` and the resolver joins `semester_assignments` on `fieldInstanceId` (the shared *groupInstanceId*) — verified: `BilanzCalculationService.groupAssignment` and `PersonResource.toChildDTO` both key groups by `fieldInstanceId`. But the spec sources the group-picker UI from the "reused, not new" `GET /api/v1/organisation/groups`. That route is served by `OrganisationResource.getByTag("groups")` and returns `OrganisationDTO.definitions` = resolved `FieldDefinition`s (each carrying the **definitionId** + label). It does **not** return the group's `fieldInstanceId`/`groupInstanceId`. So as written, the UI cannot produce the value the job is specified to store, and the resolver's join key is unobtainable from the cited endpoint. The feature's core recipient path cannot be wired from the parts named. Resolution requires either (a) an extra, unspecified definitionId→instanceId lookup (field_instances by definitionId, per GroupInstanceMigration), or (b) extending the "reused" endpoint — contradicting the "not new" claim. semester_assignments rows do carry both ids, so keying on `definitionId` is possible, but the spec commits to `groupInstanceId` without noting the gap.
- **Question the author must answer:** Where does the UI obtain the `groupInstanceId` it stores, given `GET /organisation/groups` returns only FieldDefinition ids — and if the resolver should instead key on `definitionId`, why does the schema store `recipientGroupInstanceIds`?
- **Resolution:** Resolved — schema stores `recipientGroupDefinitionIds` (the `definitionId` the endpoint returns); resolver joins `semester_assignments` on `definitionId` (rows carry it). No new endpoint, no extra lookup. (spec R15, D5, interfaces, data flow, responsibilities)

### [G-002] [Major] Placeholder-tile source has no "person property" filter, and compound fields don't substitute
- **Section:** R3, D2, Interfaces (`GET /mail-templates/placeholders`), Component Responsibilities
- **Finding:** The placeholders endpoint claims to return "one per person property, from active `FieldDefinition`s of the person basic properties." Verified: `FieldDefinition` has **no** section/category/owner field, and `FieldDefinition.findActive()` returns *every* active definition — which in this repo includes `role`, `personType` (enums), `cookingDuty` (object), six `food-property` entries, and the group definitions (`fieldName="group"`). A naive `findActive()` yields tiles for cooking duties, food flags, groups and raw enums — not "person properties." The spec gives no rule to distinguish them (keycloakMapping is set only on firstName/lastName/email; the rest have none). Separately, `address` (and `cookingDuty`) are `type:object` values; `resolveBasicValue` returns the raw value and `.toString()`s it, so `{{person.address}}` renders a BSON/Map dump, and there is no way to reference `address.street`/`zip`/`city`. D2's "regex replace `{{person.<fieldName>}}` → value" is only well-defined for scalar string fields.
- **Question the author must answer:** By exactly what predicate does the placeholders endpoint select the person-property definitions (and exclude group/cookingDuty/food-property/enum defs), and how are compound fields like `address` exposed/substituted (whole-object, blanked, or per-subfield tokens)?
- **Resolution:** Resolved — explicit scalar `fieldName` allowlist (firstName, lastName, email, phone, dateOfBirth, gender, entryDate, exitDate, notes); enum/object/group/food/cookingDuty excluded. `address` (compound) excluded from tiles in v1 (user-flagged assumption). Substitution defined for scalars only. (spec R3, D2, interfaces, Non-Goals, Assumptions)

### [G-003] [Major] ngx-quill output is class-based HTML that will not render in email clients
- **Section:** D6, R2, Success Criteria ("correctly personalized HTML email")
- **Finding:** Quill (ngx-quill) applies most formatting via CSS classes (`ql-align-center`, `ql-size-large`, `ql-indent-1`, list styling) that depend on Quill's stylesheet, not inline styles. Email clients strip `<head>`/external CSS and many strip `<style>` blocks, so class-based formatting (alignment, font-size, indent, some list rendering) silently disappears in the delivered mail — defeating R2's whole point (WYSIWYG formatting controls) while the mail still "sends." The design specifies no HTML-normalization / CSS-inlining step (e.g. a premailer/juice-equivalent) between stored `bodyHtml` and `MailService.sendHtml`. The success criterion "correctly personalized HTML email" is therefore not achievable for anything beyond bold/italic/links without an unstated inlining stage.
- **Question the author must answer:** What converts Quill's class-based HTML into email-safe inline-styled HTML before send, or is the editor constrained to only formats that emit inline styles?
- **Resolution:** Resolved — Quill configured with inline-style attributors (emits `style="…"`), toolbar limited to inline-serializable formats, plus a defensive backend HTML sanitize pass before send. Email-safe rendering added to R2 + Success Criteria. (spec D6, R2, Success Criteria, Security)

### [G-004] [Major] No guard against overlapping runs of the same job (duplicate sends)
- **Section:** D4, Data Flow (Job run), Error Handling
- **Finding:** The design registers each job as a programmatic scheduler task and, at fire, iterates recipients with sequential SMTP round-trips. If a run's duration exceeds the cron interval (short cron + slow/timing-out SMTP; note MailService uses 10s connect + 10s IO timeouts, so a handful of dead recipients can stall a run for minutes), the scheduler can fire the same job again before the prior run finishes. Quarkus scheduled tasks default to concurrent execution (PROCEED), so two runs of the same job resolve the same live recipient set and **double-send** to real parents, and race on the `lastRun*` write. The spec never specifies skip-if-running / `ConcurrentExecution.SKIP` or a per-job run lock. (Distinct from the documented horizontal-scaling double-send risk — this is single-instance.)
- **Question the author must answer:** What prevents a second fire of a job while its previous run is still executing, and where is that enforced?
- **Resolution:** Resolved — `MailJobScheduler` keeps an in-memory running-job-id set (single instance) and skips a fire whose job is already running, recording `SKIPPED_OVERLAP`; id removed in a `finally`. (spec D4, Error Handling, Data Flow steps 1/7)

### [G-005] [Major] "Validate cron server-side without scheduling" has no specified mechanism for inactive jobs
- **Section:** R13, D7, Error Handling, Data Flow (Activation)
- **Finding:** R13 requires an invalid cron to be rejected at **save** (POST/PUT) with a 400 and to leave nothing scheduled. But a job can be saved `active=false` (R17/R8) — it is *not* scheduled — so "the same parser that schedules also validates" (D7) has nothing to hook into at save time. quarkus-scheduler's programmatic API validates a cron when you actually register a job; there is no documented public "parse-and-validate-only" entry point, and the cron dialect (unix vs quartz vs cron4j) must be pinned for validation and execution to agree. The spec asserts server-side validation as settled but names no standalone parser (e.g. cron-utils) or dialect.
- **Question the author must answer:** Which concrete cron parser/dialect validates the expression on save for an *inactive* job without registering a schedule, and does it match the dialect the scheduler executes?
- **Resolution:** Resolved — pin the dialect via `quarkus.scheduler.cron-type`; validate on every save with a standalone cron-utils `CronParser` of the same dialect, independent of registration. (spec D4, R13, Error Handling)

### [G-006] [Major] Cron timezone is unspecified — "every day at 08:00" is ambiguous
- **Section:** D7, Success Criteria, R9
- **Finding:** A recurring mail schedule is meaningless without a timezone, yet neither the schema (`cron` only) nor D7 states one. Quarkus schedules in the JVM default timezone unless a zone is set per job; server default (often UTC in containers) means "08:00" fires at 10:00 local (Austria, CEST) and shifts by an hour across DST. There is no `timeZone` field on `MailJob` and no stated assumption. For a parent-facing kindergarten mailer this is a visible correctness issue, and adding a per-job timezone later is a schema change.
- **Question the author must answer:** In which timezone are cron expressions interpreted, and should it be a per-job field or a fixed application timezone?
- **Resolution:** Resolved — fixed application timezone `Europe/Vienna` (user-flagged assumption); no per-job timezone (Non-Goal). (spec D4, Assumptions, Non-Goals, Success Criteria)

### [G-007] [Major] The proposed `sendHtml` signature cannot select a sender — contradicts the "selectable account" design (D8/R16)
- **Section:** Interfaces (backend surface), D3, D8, R16, Migration & Compatibility
- **Finding:** R16/D8 make sender a stored, selectable `senderAccountId` resolved to a sender identity + credentials "at send time," and claim forward-compatibility such that when multi-account lands "only account listing/resolution change; the job schema and UI contract stay stable." But the only backend send surface given is `MailService.sendHtml(String recipient, String subject, String htmlBody)` — no account/sender parameter. Verified: `MailService` reads `MailSettings.findSingleton()` internally for From address and credentials. So today `senderAccountId` is inert (validated against nothing, ignored at send), and when a second account exists, `sendHtml`'s signature and body **must** change to accept/resolve a sender — a change to the SMTP send path, not merely "listing/resolution." The compatibility claim understates the future change and the current design stores a field that does nothing.
- **Question the author must answer:** How does `sendHtml` receive the resolved sender identity/credentials for the chosen `senderAccountId` (parameter? resolved MailSettings-like object?), and what validates that a stored `senderAccountId` is a real account?
- **Resolution:** Resolved — `senderAccountId` validated on save against `GET /mail-accounts` (today: must equal singleton id). Compat claim corrected: send path (`MailService`) *will* change to accept a resolved sender when multi-account lands; only schema/UI stay stable. Today send uses the singleton. (spec D8, D3, Migration, interfaces, Error Handling)

### [G-008] [Minor] "Reuses `resolveBasicValue`" conflicts with the batch-load performance plan, and that method is private + N+1
- **Section:** Component Responsibilities (Recipient resolver), Performance Considerations
- **Finding:** The resolver is said to "reuse existing … access (`Person.findByFamilyId`, `resolveBasicValue`)." Verified: `resolveBasicValue` and `isChild` are **private** methods of `PersonResource`, not a shared service, and each does `FieldDefinition.findById` + a `field_instances` find *per FieldRef* (N+1). Meanwhile Performance Considerations says resolution "should batch-load per run (one query per collection, join in memory) rather than per-recipient N+1." These are in tension: reusing `resolveBasicValue` *is* the N+1 pattern. Either the private methods are extracted/refactored (not "reuse") or a new batch resolver is written (not the cited method). Also no `isParent` helper exists (only `isChild`), so parent detection is net-new.
- **Question the author must answer:** Is the recipient resolver reusing the existing per-field N+1 accessors or implementing the batch join described in Performance — and which code is actually extracted/shared?
- **Resolution:** Resolved — resolver is net-new batch code following the same field-instance read *pattern* as the private `resolveBasicValue` (not literal reuse); a new `isParent` check (mirror of `isChild`) is added. Contradiction removed. (spec Component Responsibilities, Performance)

### [G-009] [Minor] Outcome of a run that resolves zero recipients is undefined
- **Section:** Data Flow step 7, Error Handling, R12
- **Finding:** Live resolution can legitimately yield zero recipients (group emptied for the current semester, no parents with non-blank email, or all children lack familyId). The status taxonomy (`SUCCESS`/`PARTIAL`/`FAILED`) has no defined value for "nothing to send," so an empty blast could record `SUCCESS` and mask a misconfiguration (wrong group, semester rollover) from the admin whose only signal is the last-run status (R12).
- **Question the author must answer:** What `lastRunStatus` does a zero-recipient run record, and is empty-resolution surfaced distinctly from a successful send?
- **Resolution:** Resolved — distinct `NO_RECIPIENTS` status, visually distinct in the Jobs UI. (spec Data Flow, Error Handling, Observability)

### [G-010] [Minor] No integrity handling when a selected group is deleted/outdated
- **Section:** Error Handling, D5, Migration & Compatibility
- **Finding:** Template-referenced-by-job is protected (409 on delete), but the symmetric case — a group FieldDefinition referenced in `recipientGroupInstanceIds` being deleted or marked `outdatedAt` — is not addressed. The job then silently resolves zero for that group with no error, indistinguishable from an empty group. No validation on save that the selected group ids exist/are active.
- **Question the author must answer:** What happens to a job whose selected group is later removed/outdated — silent skip, FAILED outcome, or save-time/lazy validation?
- **Resolution:** Resolved — save-time validation that group ids exist/active; at run a missing/outdated group contributes zero and is noted per-group in `lastRunError`. (spec Error Handling, Data Flow, interfaces)

### [G-011] [Minor] Deleted-template-at-runtime behavior is left as an open decision inside Error Handling
- **Section:** Error Handling ("auto-deactivated (or left active with FAILED status)"), Open Questions #5
- **Finding:** Error Handling states two mutually exclusive behaviors for a job referencing a deleted template and defers to OQ5. This is a decision referenced but not made; it affects whether the scheduler keeps firing a permanently-failing job. Acceptable to defer, but it is a loose end in a section that otherwise prescribes behavior. (Note: templates are also delete-protected by the 409, so this only arises via a race or a direct DB delete — worth stating that too.)
- **Question the author must answer:** On template-missing at run time, is the job auto-deactivated or left active-and-FAILED, and given the delete-guard, by what path can it even occur?
- **Resolution:** Resolved — decision made: record `FAILED` **and auto-deactivate**; can only occur via race/direct DB delete given the 409 guard. Closes Open Question #5. (spec Error Handling, Data Flow, Open Questions)

---

## Verdict
- Verdict: REVISE
- Resolved: 11 / 11
- Status: RESOLVED
