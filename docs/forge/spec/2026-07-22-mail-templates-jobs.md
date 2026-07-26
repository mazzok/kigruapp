# Feature Design Specification

<!-- One file per feature: docs/forge/spec/<YYYY-MM-DD>-<feature-slug>.md -->

- Date: 2026-07-22
- Review: required

## Goal

Admins need to send personalized email notifications to families/parents without hand-writing each message. Today the platform can only send a single plaintext email programmatically (`MailService.send`), driven by SMTP settings that were just added. This feature gives admins a self-service tool to (a) compose reusable **HTML mail templates** with a WYSIWYG editor and person-property **placeholders** (e.g. `Hi {{person.firstName}}` → `Hi Peter`), and (b) attach a template to a **recurring, cron-scheduled mail job** that sends an individually-personalized email, on a schedule, to the parents of one or more selected groups (resolved live at each run), surviving backend restarts.

## Requirements

- **R1** — Admins can create, list, view, edit, and delete **mail templates**. A template has a name and an HTML body.
- **R2** — The template editor is a WYSIWYG HTML editor offering common formatting controls (font size, style, color, bold/italic, lists, links). The stored HTML must render correctly in email clients — formatting is carried by **inline styles**, not CSS classes (G-003).
- **R3** — Alongside the editor, the UI shows a list of **placeholder tiles**, one per *scalar person property*, each labeled with its human label (de/en). Tiles are the active `FieldDefinition`s whose `fieldName` is in an explicit person-property allowlist (firstName, lastName, email, phone, dateOfBirth, gender, entryDate, exitDate, notes); enum, object, and non-person definitions (role, personType, address, cookingDuty, food-property, group) are excluded (G-002).
- **R4** — Clicking/placing a tile inserts a placeholder **token** at the cursor in the body. Tokens may appear at any position, any number of times.
- **R5** — When a mail is sent, each token is substituted with the recipient's actual value for that property. Unresolved tokens (recipient has no value) substitute to an empty string; the mail still sends.
- **R6** — Templates are persisted and reusable across multiple jobs.
- **R7** — From a selected template, an admin can **configure a job**: subject, a **sender account** (chosen from the configured mail accounts — R16), a **recipient selection** (one or more groups, or ALL — R15), and a cron schedule. A template may back more than one job, each with its own recipient selection and schedule.
- **R8** — A job can be created, listed, edited, deleted, and toggled **active/inactive**.
- **R9** — Activating a job schedules it on the backend per its cron expression; deactivating unschedules it. While active, it fires repeatedly until deactivated.
- **R10** — Job schedules **persist in the database** and are **re-armed automatically when the backend restarts**. No active job is silently lost across a restart.
- **R11** — On each fire, the backend **resolves the recipient set live** from the job's recipient selection (all valid parents in the selected group(s), or all parents for ALL), then resolves each recipient's current property values, renders the template per recipient, and sends one individualized HTML email to each. The recipient set always reflects current group membership at execution time — it is never a stored snapshot of persons.
- **R12** — A per-recipient send failure does not abort the whole run; the job records its last-run outcome (time, status, error summary) so an admin can see whether it worked.
- **R13** — The cron expression is **validated server-side** on save; an invalid expression is rejected with a clear error and does not schedule.
- **R14** — All template and job endpoints are **admin-only** (consistent with existing default-deny security).
- **R15** — Recipient selection is **by group, not by individual person**. The admin picks one or more groups (checkbox per group) or a single **ALL** option; the job stores only that selection as a list of group **definitionIds** (the id `GET /organisation/groups` returns). At execution time the backend derives the recipients: for a group, every parent (`personType==PARENT`) who has a child assigned to that group in the current semester and has a non-blank email; for ALL, every parent with a non-blank email. Individual parents are never hand-picked or stored — this keeps the job in sync with group membership without maintaining per-person, per-semester lists.
- **R16** — The job's sender is chosen from the list of **configured mail accounts**. Today exactly one account exists (the global mail settings); the design must not hard-code "one" — the selector is populated from a list endpoint so it grows when multiple accounts are supported later. The chosen account is stored on the job.
- **R17** — **Saving** a job configuration persists it as a job instance derived from the selected template. Whether it is actively scheduled is governed by its active/inactive state (R8/R9).

## Success Criteria

- An admin composes a template with at least two distinct placeholders and non-default formatting (alignment, font size), saves it, configures a job with a "every day at 08:00 (Europe/Vienna)" schedule, activates it, and each targeted recipient receives a correctly personalized HTML email at that local time with the formatting intact (inline styles survive in the client — G-003; verified in dev via GreenMail or a real SMTP sink).
- Restarting the backend while a job is active results in the job still firing on schedule afterwards (no manual re-activation).
- Deactivating a job stops further sends.
- A job whose template references a property a given recipient lacks still sends to that recipient with the token blanked, and sends to other recipients normally.
- An invalid cron expression is rejected at save time with a 400 and a message; no partial/broken schedule is left behind.
- Existing plaintext `MailService.send` behavior and the mail-settings feature are unchanged.

## Existing Architecture

Facts verified against the code (file:line):

- **Mail sending:** `service/MailService.java:29` — `send(String recipient, String subject, String body)` is the only send method; **plaintext only** (`setText(body,"UTF-8")` at `:71`). Sender (`fromAddress`/`fromName`) comes from stored `MailSettings`, not a parameter (`:64-68`). Throws unchecked `MailException` (categories `CONFIG_MISSING`, `AUTH_FAILED`, `CONNECTION_FAILED`, `UNKNOWN`) with guards for missing encryption/settings/`!enabled`. `@ApplicationScoped`, builds a fresh `jakarta.mail.Session` per send.
- **Mail settings:** `entity/MailSettings.java` — MongoDB Panache singleton (`@MongoEntity(collection="mail_settings")`, pinned `SINGLETON_ID`), password AES-encrypted via `EncryptionService`.
- **Persistence:** MongoDB Panache active-record throughout (`extends PanacheMongoEntity`, public fields, static finders). Representative CRUD pair: `entity/Semester.java` + `resource/SemesterResource.java` (no separate repository; resource calls `Semester.listAll()`, `new Semester(); .persist()`). `ObjectId` serialized via `jackson/ObjectIdModule.java`. No JPA, no relational datasource.
- **Person model:** `entity/Person.java:12-21` — a thin shell (`familyId`, `keycloakUserId`, timestamps) whose properties are `FieldRef`s grouped into sections (`basicProperties`, `roles`, `schedules`, `duties`, `finance`, `customProperties`). Each `FieldRef` = `{definitionId, fieldInstanceId}` → a `FieldDefinition` + a `FieldInstance` (value doc in `field_instances`). Seeded person properties live in `migration/FieldDefinitionSeedMigration.java`: `firstName`(Vorname), `lastName`(Nachname), `email`, `phone`, `dateOfBirth`, `gender`, `entryDate`, `exitDate`, `notes`, `address{street,zip,city}`, plus role/personType enums. `email` field instance is the recipient address (cf. `CurrentUserService.findPersonByEmail`). `FieldDefinition.findActive()` lists active definitions with i18n labels.
- **Groups / children / parents (no dedicated entities):** there is **no `Group` or `Child` class**. A *group* is a `FieldDefinition` (collection `field_definitions`) whose identity for assignments is its shared `FieldInstance` id (`groupInstanceId`), seeded by `migration/GroupInstanceMigration.java:40-49`; group definitions are listed via `GET /api/v1/organisation/groups` (`resource/OrganisationResource.java:29-37`). A *child* is a `Person` whose `basicProperties` `personType` value is `CHILD`; a *parent* is `personType==PARENT` (`resource/PersonResource.java:632-645`; enum in `migration/FieldDefinitionSeedMigration.java:57`). The **child→group** link is a per-semester row in the **`semester_assignments`** collection: `{personId, semesterId, section:"group", definitionId, fieldInstanceId, …}` (`PersonResource.java:400-426`; group key = `fieldInstanceId`, cf. `BilanzCalculationService.groupAssignment:222-234`). The **child→parent** hop: `Person.familyId` → `Person.findByFamilyId(familyId)` (`entity/Person.java:12,23-25`, `entity/Family.java:9`). Semester defaults to newest (`PersonResource.resolveSemesterId:61-70`). A parent's **email** is a `basicProperties` FieldRef with `FieldDefinition.keycloakMapping=="email"`, value read from `field_instances` (`PersonResource.resolveBasicValue:682-695`); parents may have no email instance. **No existing endpoint returns parents/families by group** — the join must be composed; building blocks: `GET /api/v1/persons/children` (`:387-398`), `GET /api/v1/families/{id}/persons` (`FamilyResource.java:52-60`).
- **Sender / mail accounts:** `MailSettings` is a **hard singleton** — fixed `SINGLETON_ID`, `findSingleton()`/`persistSingleton()` upsert so a second document cannot exist (`entity/MailSettings.java:12-37`). **No concept of multiple mail accounts / sender identities exists** anywhere. Supporting many later means dropping `SINGLETON_ID` and adding an account ref to the send path.
- **Admin auth:** `security/SecurityFilter.java` — `@Provider ContainerRequestFilter` at `@Priority(AUTHORIZATION)`, **default-deny**. Admin requests pass immediately (`:70-72`); non-admin requests pass only for an explicit whitelist of paths; everything else → 403. **Any new resource is admin-only for free** by not whitelisting it (exactly how `MailSettingsResource` is gated). Admin = `CurrentUserService.isAdmin()` (roles include `ADMIN`). Identity via Keycloak OIDC; dev bypass when `quarkus.oidc.enabled=false`. **There is no "adminpass" header** — "adminpass section" in the request means the admin-gated settings area.
- **Scheduling:** **None present** — `pom.xml` has no `quarkus-scheduler`/`quarkus-quartz`; zero `@Scheduled` usages. Quarkus 3.36.1, Java 17. No clustering config anywhere (single instance).
- **Startup hooks:** established idiom `@ApplicationScoped @Startup` bean with `void onStart(@Observes StartupEvent)` (10 migration beans, e.g. `migration/SemesterBootstrapMigration.java:19-31`), each idempotent via a `migrations` collection check.
- **Frontend:** Angular 18.2 standalone + Angular Material 18.2 (no PrimeNG/Bootstrap). Mail settings page `settings/mail/mail.component.ts`, lazy-routed under the admin-guarded `settings` parent (`app.routes.ts:90-94`, guards `[authGuard, adminGuard]`). Tabs = Material `mat-tab-group` (pattern in `settings/organisation/organisation.component.*`). HTTP via `core/services/api.service.ts` (`baseUrl='/api/v1'`); bearer token attached by `core/interceptors/auth.interceptor.ts` — **no adminpass header sent**. Service/model pattern: `shared/services/mail-settings.service.ts` + `shared/models/mail-settings.model.ts`. **No rich-text editor dependency exists** in `package.json`.

## Constraints

- Backend: Quarkus 3.36.1, Java 17, MongoDB Panache active-record (no JPA/JDBC). Follow existing entity/resource conventions.
- Frontend: Angular 18.2 standalone, Angular Material 18.2, `ApiService`/interceptor conventions.
- Reuse the existing `MailService` + `MailSettings`/`EncryptionService` for actual sending; do not introduce a second SMTP path.
- Single backend instance is the deployment model (no clustering config exists) — the scheduling design assumes exactly one scheduler process.
- Admin-only via existing `SecurityFilter` default-deny; no new auth mechanism.
- Placeholder tile set must come from the existing `FieldDefinition` data so it stays in sync with the person model.

## Design Decisions

**D1: Two new Panache entities — `MailTemplate` (`mail_templates`) and `MailJob` (`mail_jobs`).**
- Rationale: templates and jobs have independent lifecycles (one template, many jobs; R6). Matches the `Semester`-style active-record CRUD pattern already in the repo. Both admin-only for free via `SecurityFilter`.
- `MailTemplate`: `name`, `bodyHtml`, `createdAt`, `updatedAt`.
- `MailJob`: `name`, `templateId`, `subject`, `senderAccountId`, `cron`, `recipientMode` (`GROUPS` | `ALL_PARENTS`), `recipientGroupDefinitionIds` (list, used when `GROUPS`), `active`, `lastRunAt`, `lastRunStatus`, `lastRunError`, `createdAt`, `updatedAt`.
- Status: assumption (structure), see Assumptions.

**D2: Placeholder token format `{{person.<fieldName>}}` (double-brace), resolved server-side from the recipient's `basicProperties` field instances.**
- Rationale: the body is HTML, so angle-bracket tokens (`<PERSON_FIRSTNAME>` as literally requested) collide with HTML parsing and would be mangled by the WYSIWYG editor/email clients. Double-brace tokens are HTML-safe, unambiguous, and map cleanly to `FieldDefinition.fieldName`. Tiles are served from field definitions so they stay in sync with the person model (R3).
- Substitution: regex replace `{{person.<fieldName>}}` → the recipient's value; missing value → empty string (R5). Defined for **scalar** fields only; compound/object fields (`address`) are not offered as tiles and not substitutable in v1 (G-002).
- Status: assumption (the request said `<...>`, changed for HTML-safety — flag if the literal angle-bracket syntax is required).

**D3: Add an HTML-capable send path to `MailService` — do not fork a second SMTP client.**
- Rationale: templates are HTML (R2). Add e.g. `sendHtml(recipient, subject, htmlBody)` that reuses the existing session-building, encryption, and guard logic; the existing plaintext `send` stays untouched. Sender identity comes from the resolved sender account (D8) — today that is the singleton `MailSettings`, so behavior matches the current sender.
- Status: confirmed by architecture (only viable reuse path).

**D4: Scheduling via `quarkus-scheduler` programmatic API + Mongo-persisted config + `@Startup` re-arm.**
- Rationale: admin-configured, runtime-registered, per-job cron schedules require a *programmatic* scheduler (compile-time `@Scheduled` cannot express DB-driven jobs). The Quarkus `Scheduler` bean's programmatic API (`scheduler.newJob(id).setCron(cron).setTask(...).schedule()` / `unscheduleJob(id)`) supports cron and runtime (de)registration without adding Quartz. Persistence and restart-survival (R10) come from storing job config in `mail_jobs` and a `@Startup` bean that re-arms every `active` job on boot — mirroring the existing migration-bean startup idiom. Single instance means no distributed coordination is needed.
- **Overlap guard (G-004):** Quarkus scheduled tasks default to concurrent execution, and a slow run (dead recipients × MailService's 10s connect/10s IO timeouts) can outlast a short cron interval. The `MailJobScheduler` therefore keeps an in-memory set of currently-running job ids and **skips a fire whose job is already running** (single instance, so an in-memory guard is sufficient), recording a `SKIPPED_OVERLAP` note rather than double-sending.
- **Cron dialect + validation (G-005):** the cron dialect is pinned via `quarkus.scheduler.cron-type`; expressions are validated on **every save** (active or inactive job) with a standalone cron-utils `CronParser` of the *same* dialect — validation does not depend on registering a schedule.
- **Timezone (G-006):** cron expressions are interpreted in a fixed application timezone **`Europe/Vienna`** (set centrally, e.g. per-job `timeZone` on the scheduled build or a global scheduler default), not the JVM/container default. No per-job timezone field. **Assumption — flag if UTC or per-job timezones are required.**
- Status: assumption (chosen over Quartz), see Alternatives.

**D5: Recipients are selected by group and resolved live at execution — no stored person list.**
- Rationale (user-confirmed): the job stores only a recipient *selection* — a set of group `definitionId`s, or the `ALL_PARENTS` mode. `definitionId` is chosen as the stored key because that is exactly what `GET /organisation/groups` returns and what `semester_assignments` rows carry (each row has both `definitionId` and `fieldInstanceId`), so the picker value and the resolver join key match without any extra lookup or a new endpoint (G-001). At each fire the backend composes the join live: selected group `definitionId` → `semester_assignments`(section=group, current semester, `definitionId` match) → child `personId`s → `child.familyId` → `Person.findByFamilyId` → keep `personType==PARENT` with a non-blank email; `ALL_PARENTS` skips the group hop and takes every parent with a non-blank email. This keeps recipients always synchronized with current group membership and avoids maintaining per-person, per-semester snapshots (the explicit reason the user rejected hand-picking persons).
- Group candidacy is **semester-scoped** to the newest semester (the app's existing default resolution) — a parent is "in" a group for whichever semester is current at execution time.
- Multiple groups may be selected; a parent with children in more than one selected group is deduped to a single email.
- Status: confirmed by user (group-only selection, resolved live/synchronized at execution).

**D6: WYSIWYG editor = `ngx-quill` (Quill), configured to emit inline-styled, email-safe HTML.**
- Rationale: MIT-licensed, official Angular 18 support, provides the requested formatting controls out of the box, lightweight. Placeholder insertion = programmatic insert of the token text at the current selection.
- Email-safety (G-003): Quill formats via CSS **classes** by default (`ql-align-center`, `ql-size-large`, …), which email clients strip. To make delivered mail render as edited, Quill is configured with **inline-style attributors** (register `AlignStyle`/`SizeStyle`/`DirectionStyle`/etc.) so `bodyHtml` carries `style="…"` inline, and the editor's toolbar is limited to formats that serialize to inline styles. The backend additionally runs a defensive HTML sanitize pass before send.
- Status: assumption (defaulted), see Open Questions.

**D7: Cron input = presets + advanced raw-cron field; validated server-side.**
- Rationale: non-technical admins get daily/weekly/monthly presets; power users get a raw cron field. Backend is the single source of truth for validity (R13) — the same parser that schedules also validates.
- Status: assumption (defaulted), see Open Questions.

**D8: Sender is a stored reference to a selectable mail account (`senderAccountId`), resolved to the singleton today.**
- Rationale (user-confirmed): the job stores which configured mail account sends it (R16). A list endpoint returns the available accounts — today exactly one, derived from the `MailSettings` singleton (its `SINGLETON_ID` is the account id). On save, `senderAccountId` is **validated to be a real account** from that list (today: must equal the singleton id) — it is not an inert field (G-007). Today the send path uses the singleton `MailSettings` directly, so behavior matches the current sender.
- Honest forward-compat (G-007): when multiple accounts exist, the **send path itself changes** — `MailService` currently reads `MailSettings.findSingleton()` internally, so a real multi-account world requires resolving the chosen account's identity + credentials and passing them into the send call (a signature/behavior change to the SMTP path, not merely "listing/resolution"). What *stays* stable is the `MailJob` schema (`senderAccountId`) and the UI contract; the send-path change is localized to `MailService` + the sender-account provider.
- Status: confirmed by user (sender selectable from email configurations; multi-account later).

## Assumptions

- **Assumption:** the entity field sets in D1 are sufficient; `MailJob` needs no attachment support, no per-run recipient logging beyond the last-run summary. Because the requirements ask only for scheduled personalized sends with a visible last-run outcome — flag if per-run audit history is required.
- **Assumption:** placeholder tokens use `{{person.<fieldName>}}` (D2) rather than the literal `<PERSON_FIRSTNAME>` from the request, for HTML safety — flag if the exact angle-bracket syntax is a hard requirement.
- **Assumption (G-002):** placeholder tiles cover only **scalar** person properties (allowlist in R3); the compound `address` is not offered as a tile in v1 — flag if `address` (whole or as street/zip/city subfields) is needed.
- **Assumption (G-006):** cron schedules are interpreted in **`Europe/Vienna`** (fixed application timezone), not UTC or a per-job zone — flag if a different or per-job timezone is required.
- **Assumption:** "a parent in a group" is scoped to the **newest semester** (the app's default semester resolution); group membership is read live at each execution — flag if a specific/fixed semester should be targeted instead.
- **Assumption:** a parent is identified by `personType==PARENT` (not the `roles` ADMIN/PARENT enum), matching `PersonResource`'s child/parent detection — flag if role-based targeting is meant.
- **Assumption:** admins-without-a-child (e.g. staff) are not part of any group and are only reachable via `ALL_PARENTS` if they have `personType==PARENT` + email; pure admins are not mailed — flag if admins should be targetable.
- **Assumption:** `ngx-quill` is an acceptable new frontend dependency (D6) — defaulted.
- **Assumption:** cron uses presets + advanced raw field (D7) — defaulted.
- **Assumption:** single backend instance — no two schedulers will race to double-send. True today (no clustering config); flag before any horizontal scaling.
- **Assumption:** a schedule missed because the server was down during its fire time is simply skipped (no catch-up/misfire replay) — acceptable for notification mail.

## Component Responsibilities

- **`MailTemplate` entity + `MailTemplateResource`** — CRUD for templates; serves the placeholder-tile list (from `FieldDefinition`).
- **`MailJob` entity + `MailJobResource`** — CRUD for jobs; activate/deactivate; cron validation on write.
- **`MailService` (extended)** — adds the HTML send path (D3). Still the single SMTP integration point.
- **Template renderer (backend service)** — given a template body + a resolved property map for one recipient, produces the final HTML (token substitution). Pure/testable, no I/O.
- **Recipient resolver (backend service)** — **net-new** service that resolves the live recipient set from a `MailJob`'s selection: for `GROUPS`, composes `semester_assignments`(section=group, current semester, `definitionId` match) → child persons → families → parent persons (`personType==PARENT`) with a non-blank email, for each selected group `definitionId`, deduped; for `ALL_PARENTS`, every parent with a non-blank email. Returns `(recipient email, property map)` per recipient. It performs a **batch join** (one query per collection, joined in memory — see Performance), *following the same field-instance read pattern* as `PersonResource.resolveBasicValue` rather than reusing that private N+1 method (G-008); a new `isParent` check (mirror of the existing private `isChild`) is introduced. Reuses `Person.findByFamilyId`; no new group entity.
- **Sender-account provider (backend)** — returns the list of selectable mail accounts (today one, from the `MailSettings` singleton) and resolves a `senderAccountId` to a concrete sender identity + credentials for `MailService` (D8/R16).
- **`MailJobScheduler` (backend, `@ApplicationScoped`)** — owns registration/unregistration of jobs with the Quarkus `Scheduler`; exposes `schedule(job)`, `unschedule(jobId)`, and the per-fire `run(jobId)` task that orchestrates resolve → render → send → record-outcome.
- **`MailJobStartupRearmer` (backend, `@Startup`)** — on boot, loads every `active` job and calls `schedule(job)`. Mirrors the migration-bean startup idiom (R10).
- **Frontend `MailComponent` (extended)** — gains `mat-tab-group`: existing SMTP settings tab + a **Mail-templates** tab (list, ngx-quill editor, placeholder tiles) + a **Jobs** view (list, configure-job form reachable from a selected template with: subject, sender-account dropdown, group multiselect + ALL option, cron presets/advanced, activate toggle, last-run status). Group list comes from the existing `GET /api/v1/organisation/groups`.
- **Frontend services/models** — `mail-template.service.ts`/`.model.ts` and `mail-job.service.ts`/`.model.ts`, following the `mail-settings` pattern over `ApiService`.

## Interfaces

REST (all under `/api/v1`, admin-only via `SecurityFilter`):

- **Templates**
  - `GET  /mail-templates` → list `{id, name, updatedAt}`
  - `GET  /mail-templates/{id}` → `{id, name, bodyHtml, createdAt, updatedAt}`
  - `POST /mail-templates` `{name, bodyHtml}` → created template
  - `PUT  /mail-templates/{id}` `{name, bodyHtml}` → updated template
  - `DELETE /mail-templates/{id}` → 204 (409/400 if referenced by a job — see Error Handling)
  - `GET  /mail-templates/placeholders` → `[{token:"{{person.firstName}}", fieldName, label:{de,en}}]` — active `FieldDefinition`s filtered to the scalar person-property `fieldName` allowlist (G-002); excludes enum/object/group/food-property/cookingDuty defs
- **Jobs**
  - `GET  /mail-jobs` → list `{id, name, templateId, active, cron, lastRunAt, lastRunStatus}`
  - `GET  /mail-jobs/{id}` → full job
  - `POST /mail-jobs` `{name, templateId, subject, senderAccountId, cron, recipientMode, recipientGroupDefinitionIds[]}` → created; validated on save: cron (400 on invalid — G-005), `senderAccountId` must be a real account (G-007), each group `definitionId` must exist and be active (G-010)
  - `PUT  /mail-jobs/{id}` `{...}` → updated (if active and cron changed, re-arm)
  - `DELETE /mail-jobs/{id}` → 204 (unschedules first if active)
  - `POST /mail-jobs/{id}/activate` → sets `active=true`, schedules
  - `POST /mail-jobs/{id}/deactivate` → sets `active=false`, unschedules
- **Sender accounts**
  - `GET /mail-accounts` → `[{id, fromAddress, fromName, enabled}]` — today one entry (the `MailSettings` singleton, `id == SINGLETON_ID`); grows when multi-account is added (R16/D8).
- **Groups (reused, not new)**
  - `GET /organisation/groups` (existing) → group definitions for the recipient selection UI; each carries the `definitionId` that the job stores in `recipientGroupDefinitionIds` (G-001).

Backend service surface (indicative, not prescriptive):
- `MailService.sendHtml(String recipient, String subject, String htmlBody)` — today resolves sender from the singleton `MailSettings` (matching current behavior). When multi-account arrives this gains a resolved-sender parameter (G-007); the change is confined to `MailService` + the sender-account provider.

## Data Flow

Job run (one fire):
1. Scheduler task fires for `jobId` → if this job id is already in the in-memory running-set, record `SKIPPED_OVERLAP` and return (G-004); otherwise mark it running. Load `MailJob`; if not `active`, no-op.
2. Load referenced `MailTemplate`; if missing, record `FAILED` + auto-deactivate, stop (G-011).
3. Recipient resolver → live-resolve the recipient set from `recipientMode`/`recipientGroupDefinitionIds` (group `definitionId`→semester_assignments→children→families→parents-with-email, or all parents), deduped → `(email, propertyMap)` per recipient. If the set is empty, record `NO_RECIPIENTS` and stop (G-009). A selected group that no longer exists/active contributes zero and is noted per-group in the outcome (G-010).
4. Resolve sender identity + credentials from `senderAccountId` (today the singleton).
5. For each recipient: renderer substitutes `{{person.*}}` tokens in `bodyHtml` with `propertyMap` values (missing → empty); `MailService.sendHtml(email, job.subject, renderedHtml)`.
6. Individual failures are caught and counted; the run continues.
7. Record `lastRunAt`, `lastRunStatus` (`SUCCESS` / `PARTIAL` / `FAILED` / `NO_RECIPIENTS` / `SKIPPED_OVERLAP`), `lastRunError` summary; persist job; remove the job id from the running-set (in a `finally`, so a crash never leaves it stuck marked-running).

Activation:
1. `POST /mail-jobs/{id}/activate` → validate cron → `scheduler.schedule(job)` → persist `active=true`.

Startup:
1. `@Startup` re-armer loads all `active` jobs → `scheduler.schedule(job)` each.

## Error Handling

- **Invalid cron** on create/update/activate → 400 with message; nothing scheduled (R13). Validated on every save via a standalone cron-utils parser of the pinned dialect, independent of whether the job is active (G-005).
- **Invalid `senderAccountId` or unknown/outdated group `definitionId`** on save → 400; the id must resolve to a real account (G-007) / an existing active group (G-010).
- **Template referenced by a job**, on template delete → reject (409/400) with which job(s) reference it; do not orphan jobs.
- **`MailSettings` disabled/misconfigured/encryption key missing** at run time → `MailService` throws `MailException`; the run catches it, records `lastRunStatus=FAILED` + category, and the scheduler continues (never crashes the scheduler thread).
- **Per-recipient send failure** (bad address, transient SMTP) → caught, counted, run continues; outcome becomes `PARTIAL` if some succeeded, `FAILED` if none.
- **Zero recipients resolved** (empty group, no parents with email, semester rollover) → run records the distinct status **`NO_RECIPIENTS`** (not `SUCCESS`), so a misconfiguration is visible to the admin (G-009). A selected group that is missing/outdated contributes zero and is noted per-group in `lastRunError` (G-010).
- **Recipient with no email / no value for a token** → excluded from recipients (no email) / token blanked (no value); never an exception.
- **Overlapping fire** (previous run still executing) → the new fire is skipped and recorded as `SKIPPED_OVERLAP`; no double-send (G-004).
- **Job references a deleted template at run time** → record `lastRunStatus=FAILED` **and auto-deactivate** the job (stops a permanently-failing schedule). Given the 409 delete-guard, this can only arise via a race or a direct DB delete (G-011).
- **Duplicate activation / double schedule** → scheduler unschedules any existing registration for the id before scheduling (idempotent arm).

## Migration & Compatibility

- Additive/greenfield: two new collections (`mail_templates`, `mail_jobs`), no changes to existing documents. No data migration. Reads existing `semester_assignments`, `families`, `persons`, `field_instances` for recipient resolution (read-only).
- New backend dependency: `quarkus-scheduler`. New frontend dependency: `ngx-quill` (+ Quill). Both isolated to this feature.
- The `senderAccountId` reference is forward-compatible at the **schema/UI** level: existing jobs keep pointing at the (then-migrated) singleton account id. The **send path** (`MailService`) will change to resolve and apply the chosen account's identity/credentials when multi-account lands — this is localized and expected, not "no change" (G-007).
- Existing `MailService.send` (plaintext) and the mail-settings feature are untouched; the HTML path is purely additive.
- Rollback: remove the routes/tab and the two collections; no other feature depends on them.

## Security Considerations

- **Authorization:** all endpoints admin-only via existing default-deny `SecurityFilter` (R14); no new auth surface. Non-admins get 403.
- **PII in mail:** placeholder resolution reads person PII (name, email, phone, DOB, address) into outgoing mail. Only admins can configure/trigger this; acceptable within the existing trust model, but worth an explicit note since it broadens where PII flows.
- **HTML/template injection:** template bodies are admin-authored (trusted) but pass through a **backend sanitize pass** before send (G-003) that also strips anything unsafe. Placeholder *values* come from user data and are injected into HTML; they are HTML-escaped on substitution to prevent a malformed stored value from breaking layout or injecting markup (defensive, low risk since admin-only).
- **Sender identity:** the From address is not free-typed — it comes from a configured mail account (D8), so an admin cannot send as an arbitrary address through this feature. Deliverability still depends on the SMTP server's SPF/DKIM for the configured account.
- **Cron as input:** validated server-side; no expression is executed as code (it configures the scheduler only) — no injection vector.
- **No SSRF/new network egress** beyond the already-configured SMTP host.

## Performance Considerations

- A run iterates the job's configured recipients (order of tens–low hundreds for a kindergarten) and sends sequentially via one SMTP session per send — acceptable at this scale; a run completes in seconds to low minutes.
- The default Quarkus scheduler runs tasks on a shared worker; a long run could delay other jobs. Mitigation if needed: run the send loop on a worker thread / mark the task non-blocking-offloaded. First bottleneck is SMTP round-trip latency × recipient count.
- Recipient resolution runs a multi-hop join (`semester_assignments` → persons → families → parents → email instances) **on every fire**. At kindergarten scale this is small, but it should batch-load per run (one query per collection, join in memory) rather than per-recipient N+1 queries. The existing `BilanzCalculationService` demonstrates the same joins at acceptable cost.

## Observability

- Per-job `lastRunAt` / `lastRunStatus` (`SUCCESS`/`PARTIAL`/`FAILED`/`NO_RECIPIENTS`/`SKIPPED_OVERLAP`) / `lastRunError` surfaced in the Jobs UI (R12) — the primary operator signal. `NO_RECIPIENTS` and `SKIPPED_OVERLAP` are visually distinct from `SUCCESS` so a misconfiguration or overload is noticeable (G-009, G-004).
- Backend logs per run: job id, recipient count, success/fail counts, and any `MailException` category. Reuse existing logging conventions.
- Startup log line listing which jobs were re-armed (confirms R10 visibly).

## Alternatives Considered

- **Quartz (`quarkus-quartz`) instead of the default scheduler (D4).** Steelman: Quartz offers misfire handling and a persistent job store. Rejected: its persistent store needs JDBC, which this MongoDB-only stack lacks; a RAM store would still need the same Mongo-config + startup re-arm we already build. It adds a dependency and moving parts for capabilities (misfire replay, clustering) the single-instance deployment does not use. The programmatic `quarkus-scheduler` API covers runtime cron registration with less surface.
- **Compile-time `@Scheduled` with one generic sweeper that polls the DB (D4).** Steelman: no runtime registration needed. Rejected: it can't honor arbitrary per-job cron expressions (each job's cadence is data, not an annotation); you'd re-implement a cron engine over a polling loop.
- **Literal `<PERSON_FIRSTNAME>` angle-bracket tokens (D2).** Steelman: matches the request verbatim and reads naturally. Rejected: collides with HTML parsing in a WYSIWYG editor and email clients; error-prone. Double-brace is the standard HTML-safe choice.
- **Hand-picked / snapshotted person recipients instead of live group resolution (D5).** Steelman: precise control, an auditable recipient list, ability to exclude a specific parent. Rejected by the user: maintaining per-person lists against per-semester `semester_assignments` is hard to keep correct as children move groups or semesters roll over. Live group resolution keeps the job automatically in sync; the cost is no per-person exclusion (a Non-Goal).
- **Store rendered emails / full per-run audit log.** Rejected for this iteration: requirements ask only for a visible last-run outcome; full audit history is a Non-Goal (can be added later without schema conflict).
- **Third-party editors (TinyMCE/CKEditor) (D6).** Rejected: heavier and licensing/attribution overhead vs. MIT `ngx-quill` for the same needed feature set.
- **Single combined entity for template+job (D1).** Steelman: fewer collections, simpler CRUD. Rejected: one template is reused by many jobs (R6); merging them forces body duplication and couples independent lifecycles.
- **Separate HTML mailer class instead of extending `MailService` (D3).** Steelman: keeps `MailService` minimal. Rejected: it would duplicate session-building, encryption, and guard logic and create a second SMTP path to keep in sync — the reuse rule favors extension.
- **Raw-cron-only or presets-only input (D7).** Steelman: each is simpler to build. Rejected: raw-only is unfriendly to non-technical admins; presets-only cannot express arbitrary schedules. The combined form covers both audiences at modest cost.

## Risks

- **Double-send if horizontally scaled.** Two instances would each re-arm and fire every job. Mitigation: documented single-instance assumption; before scaling, add a distributed lock or a leader election around the scheduler.
- **Missed fires during downtime.** A schedule due while the server is down does not replay. Mitigation: accepted for notifications; documented. If catch-up is needed later, move to Quartz misfire handling.
- **Large recipient blasts / SMTP rate limits.** A big org + frequent cron could hit provider limits or slow runs. Mitigation: sequential send keeps it gentle; add throttling/batching if it becomes an issue.
- **Template/field drift.** A token references a `fieldName` later removed from field definitions. Mitigation: unresolved tokens blank out (R5); optionally surface "unknown tokens" at template save.
- **Defaulted decisions (D6 editor, D7 cron UX, D2 token syntax, D5 semester scope).** Chosen without explicit confirmation; if wrong, they touch a dependency choice, the persisted token format, and recipient resolution. Mitigation: flagged in Open Questions; confirm before implement.

## Non Goals

- Hand-picking or excluding **individual** recipients within a group — targeting is group-level (or ALL) only; the backend resolves the parent set live (D5). Explicitly rejected by the user to avoid per-person, per-semester maintenance.
- Free-typed email recipients not backed by a person record (would not personalize).
- Segmenting by team or arbitrary role, or targeting a fixed historical semester.
- Placeholder tokens for compound/object fields (`address`) or per-subfield tokens — scalar person properties only (G-002).
- Per-job timezones — a single fixed application timezone is used (G-006).
- Full per-run audit history / stored copies of every sent email.
- One-off (non-recurring) "send now" campaigns and A/B testing.
- Attachments, inline images beyond what the editor embeds, or multi-language template variants.
- Bounce/open/click tracking and deliverability analytics.
- Clustered/HA scheduling and misfire catch-up.
- Recipient opt-out/unsubscribe management.

## Open Questions

1. **Editor library (D6)** — defaulted to `ngx-quill`. Confirm the new dependency is acceptable.
2. **Cron UX (D7)** — defaulted to presets + advanced raw-cron field. Confirm scope.
3. **Placeholder token syntax (D2)** — changed the requested `<PERSON_FIRSTNAME>` to `{{person.firstName}}` for HTML safety. Confirm acceptable.
4. **Semester scope (D5)** — group membership resolves against the newest semester at execution time. Confirm that's the intended behavior (vs. a fixed semester).

## Changelog

- 2026-07-22 — Recipients changed from auto-resolved "all parents (live)" to an explicit per-job hand-picked person list, chosen at configure time; confirmed a template may back multiple jobs (R7, D5, R11, interfaces, data flow). — manual (user answer during design)
- 2026-07-22 — Sender changed from free-text from-name/from-address override to a selectable `senderAccountId` referencing a configured mail account (one today = the `MailSettings` singleton; multi-account later); added `GET /mail-accounts` (R16, D8, D3, interfaces). — manual (user direction during design)
- 2026-07-22 — Recipients changed again from hand-picked persons to **group-only selection resolved live at execution** (`recipientMode`=`GROUPS`/`ALL_PARENTS` + `recipientGroupInstanceIds`); dropped the person picker / `recipientPersonIds` / recipient-candidate endpoint; reuses `GET /organisation/groups` for the group list (R7, R11, R15, D5, interfaces, data flow, non-goals). — manual (user direction during design)
- 2026-07-22 — Grill review resolved (11/11), Status: RESOLVED. Group key switched to `recipientGroupDefinitionIds` so the stored id matches what `GET /organisation/groups` returns and the `semester_assignments` join key (resolves G-001). Placeholder tiles restricted to a scalar person-property allowlist, `address` excluded (G-002). Quill configured to emit inline-styled email-safe HTML + backend sanitize (G-003). Per-job skip-if-running overlap guard (G-004). Cron validated on every save via pinned-dialect cron-utils parser (G-005). Fixed `Europe/Vienna` cron timezone (G-006). `senderAccountId` validated on save + honest send-path forward-compat (G-007). Recipient resolver clarified as net-new batch code, `isParent` added (G-008). `NO_RECIPIENTS`/`SKIPPED_OVERLAP` run statuses (G-009). Save-time group-id validation + per-group run notes (G-010). Deleted-template-at-runtime → FAILED + auto-deactivate, closes OQ5 (G-011). — resolves G-001…G-011 / forge-review
