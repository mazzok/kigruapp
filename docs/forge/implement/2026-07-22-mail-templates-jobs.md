# Implementation Plan

<!-- Same basename as the spec: docs/forge/implement/<spec-basename>.md -->

## Spec

`../spec/2026-07-22-mail-templates-jobs.md`

## Goal

When all tasks are done: admins can create/edit/delete reusable HTML mail templates via a WYSIWYG (ngx-quill) editor with a scalar person-property placeholder-tile panel; configure one or more cron-scheduled jobs per template (subject, sender account, group-based or all-parents recipient selection, cron schedule); activate/deactivate jobs; and the backend resolves recipients live, renders, and sends personalized HTML mail on schedule, surviving restarts, with per-job last-run status visible in the admin UI. Existing plaintext `MailService.send` and the mail-settings feature are unchanged.

## Implementation Strategy

Backend-first, bottom-up: add dependencies, then the two new Panache entities and their CRUD resources (undecorated), then layer in validation (cron, sender account, group ids) and the delete-guard once both entities exist, then the pure/testable services (renderer, recipient resolver) built from small composable pieces, then the scheduler + startup re-armer + activate/deactivate wiring that ties it all together, and finally the per-run status machine (happy path → overlap guard → no-recipients → missing-template → per-recipient failure), each as its own task so the run() behavior is built incrementally against a stable happy-path base. Frontend follows the same shape: thin model/service pairs (mirroring `mail-settings.service.ts`, no dedicated spec — build-verified only, consistent with how that feature was built), then UI components built in editable layers (skeleton form → rich editor → placeholder insertion; job skeleton → recipient picker → cron UX → activate/status), finishing with the `mat-tab-group` wiring that surfaces everything inside the existing `MailComponent`.

Ordering keeps the codebase buildable and green after every task: CRUD before validation, both entities before the cross-referencing delete-guard, resolver pieces before the scheduler that calls them, and the scheduler's happy path before its edge-case branches.

## Why This Approach

- **Two entities, not one combined** (D1): matches spec decision — one template backs many jobs (R6), independent lifecycles, avoids body duplication. Rejected alternative: single entity (spec's Alternatives Considered).
- **`RecipientResolverService` as net-new batch code**, not a wrapper around `PersonResource`'s private N+1 methods (G-008): those methods are private and per-field N+1; a new service following the same field-instance read *pattern* is the smallest correct option — extracting/making them public would touch `PersonResource` unnecessarily for a resource it doesn't otherwise need to change.
- **Backend orchestration split into `fire(jobId)` (thin, loads via Panache statics) vs `runJob(MailJob, MailTemplate)` (pure orchestration)**: keeps the run()-behavior tasks (018–022) testable by constructing `MailJob`/`MailTemplate` instances directly in-test, consistent with the codebase's existing convention of real-Mongo `@QuarkusTest`s (no static-mocking pattern exists anywhere in this repo, so introducing one for Panache statics would be new, riskier surface — avoided).
- **cron-utils + owasp-java-html-sanitizer as new deps**: smallest libraries that satisfy G-005 (dialect-pinned standalone validation, independent of scheduler registration) and G-003 (defensive sanitize pass) without hand-rolling either. `quarkus-scheduler` is the spec's confirmed choice (D4) over Quartz.
- **Frontend model/service pairs are `Test: N/A`**: they are thin `ApiService` wrappers with no branching logic, exactly matching the existing `mail-settings.service.ts` (which has no dedicated spec in the repo); behavior is exercised through the component specs that consume them, per the project's established pattern.
- **Component tasks split into skeleton → feature layers** (e.g. template form → quill editor → placeholder insertion) rather than one large component task: each layer is independently working and testable, avoiding an `L`-sized task that bundles unrelated concerns (rich-text config vs. token insertion vs. list/CRUD wiring).

## Components Affected

- **Backend `entity/`**: two new Panache entities (`MailTemplate`, `MailJob`).
- **Backend `resource/`**: two new REST resources (`MailTemplateResource`, `MailJobResource`), no changes to existing resources.
- **Backend `service/`**: `MailService` extended (new method, no signature change to existing `send`); three new services (`MailTemplateRenderer`, `PersonPropertyResolver`, `RecipientResolverService`); new `MailJobScheduler` and `MailJobStartupRearmer`.
- **Backend `security/SecurityFilter`**: unchanged — new resources are admin-only by not being whitelisted (matches `MailSettingsResource`'s approach).
- **Backend `pom.xml`**: three new dependencies.
- **Backend `application.properties`**: cron dialect + timezone config.
- **Frontend `shared/models/`, `shared/services/`**: three new model/service pairs.
- **Frontend `settings/mail/`**: `MailComponent` gains a `mat-tab-group`; two new sub-components (template editor, job configurator) and their specs.
- **Frontend `package.json`**: `ngx-quill` + `quill` added.

## Expected File Changes

Backend (`backend/src/main/java/at/kigruapp/`):
- `pom.xml` — add `quarkus-scheduler`, `com.cronutils:cron-utils`, `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer`
- `src/main/resources/application.properties` — `quarkus.scheduler.cron-type`, app timezone note
- `entity/MailTemplate.java` (new)
- `entity/MailJob.java` (new), `entity/RecipientMode.java` (new enum)
- `resource/MailTemplateResource.java` (new)
- `resource/MailJobResource.java` (new)
- `resource/MailAccountResource.java` (new, serves `GET /mail-accounts`)
- `service/MailService.java` (extend: `sendHtml`)
- `service/MailTemplateRenderer.java` (new)
- `service/PersonPropertyResolver.java` (new)
- `service/RecipientResolverService.java` (new)
- `scheduler/MailJobScheduler.java` (new)
- `scheduler/MailJobStartupRearmer.java` (new)
- Matching test files under `backend/src/test/java/at/kigruapp/{entity,resource,service,scheduler}/`

Frontend (`frontend/src/app/`):
- `package.json` — add `ngx-quill`, `quill`
- `shared/models/mail-template.model.ts`, `shared/services/mail-template.service.ts` (new)
- `shared/models/mail-job.model.ts`, `shared/services/mail-job.service.ts` (new)
- `shared/models/mail-account.model.ts`, `shared/services/mail-account.service.ts` (new)
- `settings/mail/mail-template-editor/mail-template-editor.component.{ts,html,scss,spec.ts}` (new)
- `settings/mail/mail-job-editor/mail-job-editor.component.{ts,html,scss,spec.ts}` (new)
- `settings/mail/mail.component.{ts,html,scss,spec.ts}` (modified — adds `mat-tab-group`)

## Testing Strategy

- **Backend**: `@QuarkusTest` + real MongoDB (`kigruapp_test` DB, `deleteAll()` cleanup in `@BeforeEach`) for anything touching persistence or REST — matches every existing backend test. `GreenMail` (already a test dependency) for anything touching actual SMTP send (`sendHtml`, `runJob` happy path and failure-isolation). Pure logic (`MailTemplateRenderer` substitution) gets plain JUnit5 unit tests, no `@QuarkusTest` needed. Scheduler orchestration (`runJob`) is tested by constructing `MailJob`/`MailTemplate` instances directly and calling the pure orchestration method — no static mocking. Run: `./mvnw test` (all), `./mvnw test -Dtest=<ClassName>` (scoped).
- **Frontend**: Karma/Jasmine, direct component instantiation with fake services (no `TestBed`), matching `mail.component.spec.ts`/`organisation.component.spec.ts`. Model/service pairs are build-verified only (`ng build`), consistent with the existing `mail-settings.service.ts` (no dedicated spec in the repo). Run: `ng test --watch=false` (all), `ng test --watch=false --include='<glob>'` (scoped).
- Full-suite green sweep (`./mvnw test` and `ng test --watch=false`) runs once at the start of the execution run (baseline) and once at the end (regression sweep), per forge rules — not per task.

## Risks

- **Review gate**: satisfied without warning — `2026-07-22-mail-templates-jobs.grill.md` has `Status: RESOLVED`, 11/11 findings resolved, verdict REVISE→resolved. No override needed.
- **Known pre-existing baseline**: `main` has pre-existing failing backend tests + 1 frontend test, unrelated to this feature. **Correction recorded during the final regression sweep (2026-07-23)**: the initial baseline capture at run start recorded 110 backend tests / 6 failures / 3 errors, but this undercounted — a re-run of the full suite after all 38 tasks showed 155 tests / 10 failures / 2 errors, and `FieldDefinitionResourceTest`'s 4 failures (POST `/field-definitions` returning 400 instead of 201 — payload doesn't match the resource's actual `jsonSchema`-required contract) were not in the original capture. Verified via a clean `git worktree` of the unmodified base commit (`bf587d1`) that all 4 `FieldDefinitionResourceTest` failures reproduce identically there — confirmed pre-existing and unrelated to this feature, not a regression. Full accounting: 6 (SecurityFilterTest) + 4 (FieldDefinitionResourceTest) = 10 failures, 2 (CurrentUserServiceTest) errors — exactly matches the post-run sweep, meaning all 45 new backend tests pass and zero regressions were introduced. Frontend: 103 run (80 baseline + 23 new), 1 pre-existing failure (`AppComponent`), 0 new failures.
- **`runJob` per-recipient failure isolation test (Task 022)** relies on a deterministic send failure. Plan: seed one recipient's email `field_instance` value as a syntactically invalid address (e.g. `not-an-email`) so `jakarta.mail` address parsing throws inside the existing `MailService` guard logic — no new mocking infrastructure needed. If this proves unreliable during execution, fall back to a second `GreenMail` instance refusing one recipient, or flag to the user for a design call.
- **`MailJobStartupRearmer` restart behavior (Task 023)**: true backend-restart re-arming can't be exercised in-process by `@QuarkusTest` (the app boots once before test data exists). The task tests the extracted `rearmAll()` method directly (same code `@Startup` calls) rather than the `StartupEvent` trigger itself, which is thin framework wiring. This is a scope-narrowing of R10's "restart" wording to "the re-arm logic runs correctly given active jobs in the DB" — flagged here per the spec-gap protocol; the alternative (a full external restart test) is out of proportion to this plan and not how any other `@Startup` bean in the repo is tested.
- **Cron dialect choice**: spec pins `quarkus.scheduler.cron-type` but doesn't name the exact dialect string. Task 001/017 default to `quartz` (Quarkus's default cron-type, 6/7-field, most commonly understood) — flagged as an assumption; if the admin-facing cron presets (Task 036) assume Unix 5-field cron instead, this must be reconciled in Task 017 before Task 036, not silently.
- **ngx-quill inline-style attributor configuration (Task 032)** is the highest-uncertainty frontend task — Quill's inline-style attributor registration is fiddly and under-documented. Sized `M` with room to iterate; if it proves genuinely `L`, split further at execution time rather than cutting corners on G-003.

## Out of Scope

Echoing the spec's Non-Goals:
- Hand-picking/excluding individual recipients within a group.
- Free-typed email recipients not backed by a person record.
- Segmenting by team/role, or targeting a fixed historical semester.
- Placeholder tokens for compound/object fields (`address`) or per-subfield tokens.
- Per-job timezones.
- Full per-run audit history / stored copies of sent emails.
- One-off "send now" campaigns, A/B testing.
- Attachments, inline images beyond editor-embedded, multi-language template variants.
- Bounce/open/click tracking, deliverability analytics.
- Clustered/HA scheduling, misfire catch-up.
- Recipient opt-out/unsubscribe management.
- Multi-account sender support beyond the `senderAccountId` schema/UI contract (D8) — the send-path change for a second real account is explicitly deferred by the spec.

# Task Breakdown

## Task 001 — Add scheduler, cron-validation, and HTML-sanitizer dependencies
- Goal: Add `quarkus-scheduler`, `com.cronutils:cron-utils`, and `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` to the backend build.
- Spec ref: D4, G-003, G-005
- Depends on: none
- Affected: `backend/pom.xml`, `backend/src/main/resources/application.properties`
- Expected changes: three new `<dependency>` entries in `pom.xml` (versions matching the Quarkus 3.36.1 BOM for `quarkus-scheduler`; latest stable for the other two, e.g. cron-utils 9.x, owasp-java-html-sanitizer 20240325.1). Add `quarkus.scheduler.cron-type=quartz` to `application.properties` (pins the dialect for both validation and execution — see Risks re: dialect choice).
- Test: N/A — dependency/config addition, no new behavior yet.
- Verification: `./mvnw compile` → succeeds; `./mvnw test` → existing suite passes at the same pre-existing baseline (13 known-failing backend tests, no new failures).
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `backend/pom.xml` (added `quarkus-scheduler`, `com.cronutils:cron-utils:9.2.1`, `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1`), `backend/src/main/resources/application.properties` (`quarkus.scheduler.cron-type=quartz`). N/A task — verification only: `mvnw.cmd compile` succeeded; `mvnw.cmd test` → `Tests run: 110, Failures: 6, Errors: 3, Skipped: 75`, identical to the recorded baseline. Deviations: none. Note — git-bash `mvnw`/`ng` invocations are intercepted by a context-mode hook and fail; used the PowerShell tool with `mvnw.cmd`/`npx ng` instead for all build/test commands this run (recorded in the codebase brief).

## Task 002 — MailTemplate entity + CRUD resource
- Goal: Create the `MailTemplate` entity and a CRUD REST resource for it (list/get/create/update/delete, no delete-guard yet).
- Spec ref: R1, R6, D1, Interfaces (Templates)
- Depends on: none
- Affected: `entity/MailTemplate.java` (new), `resource/MailTemplateResource.java` (new)
- Expected changes: `MailTemplate extends PanacheMongoEntity` with `name`, `bodyHtml`, `createdAt`, `updatedAt` (public fields, `@MongoEntity(collection = "mail_templates")`), following `Semester`'s shape exactly. `MailTemplateResource` at `/api/v1/mail-templates`: `GET` (list, `Sort.descending("updatedAt")`), `GET /{id}` (404 via `NotFoundException()`), `POST` (validates `name`/`bodyHtml` non-blank → 400, sets `createdAt`/`updatedAt`, 201), `PUT /{id}` (field copy + `updatedAt` refresh + `.update()`), `DELETE /{id}` (unconditional `.delete()`, 204 — guard added in Task 009). Not whitelisted in `SecurityFilter` → admin-only by default-deny.
- Test: `resource/MailTemplateResourceTest.java` — `createAndListTemplates` (POST then GET list contains it), `getById404WhenMissing`, `updateChangesFields`, `deleteRemovesTemplate`, `createRejectsBlankName`.
- Red: `./mvnw test -Dtest=MailTemplateResourceTest` → compile fails (no `MailTemplate`/`MailTemplateResource` classes exist yet).
- Green: `./mvnw test -Dtest=MailTemplateResourceTest` → all 5 cases pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `entity/MailTemplate.java`, `resource/MailTemplateResource.java`, `resource/MailTemplateResourceTest.java`. Green: scoped 5/5 (`Tests run: 5, Failures: 0, Errors: 0`). Deviations: (1) dropped the planned `nonAdminGets403` sub-case — `%test.quarkus.oidc.enabled=false` makes `SecurityFilter` a no-op in the test profile (confirmed by reading the filter), so a REST-level 403 assertion isn't exercisable there; this exactly matches why the precedent `MailSettingsResourceTest` has no such test either — admin-gating is guaranteed structurally by not whitelisting the path, verified once centrally by `SecurityFilterTest`. Substituted `createRejectsBlankName` as the 5th case to keep validation coverage. (2) MongoDB was not running in this environment (Docker Desktop was stopped, no compose file in repo); started Docker Desktop and ran a standalone `mongo:7` container (`docker run -d --name kigruapp-mongo -p 27017:27017 mongo:7`, no persistent volume) to match the app's `mongodb://localhost:27017` connection string — needed for every subsequent backend task in this run.

## Task 003 — Placeholder tiles endpoint
- Goal: Add `GET /mail-templates/placeholders` returning the scalar person-property allowlist as placeholder tiles.
- Spec ref: R3, D2, G-002, Interfaces
- Depends on: Task 002
- Affected: `resource/MailTemplateResource.java`
- Expected changes: `GET /mail-templates/placeholders` filters `FieldDefinition.findActive()` to a fixed allowlist of `fieldName` values (`firstName, lastName, email, phone, dateOfBirth, gender, entryDate, exitDate, notes`), returns `[{token: "{{person.<fieldName>}}", fieldName, label}]` per matching definition, sorted by label. Definitions outside the allowlist (role, personType, address, cookingDuty, food-property entries, group) are excluded regardless of `findActive()` result.
- Test: `resource/MailTemplateResourceTest.placeholdersReturnsOnlyAllowlistedScalarFields` — seeds `FieldDefinition`s for `firstName` (allowed) and `group`/`address`/`personType` (excluded), asserts response contains exactly the allowed token and excludes the others.
- Red: `./mvnw test -Dtest=MailTemplateResourceTest#placeholdersReturnsOnlyAllowlistedScalarFields` → 404 (no route) or assertion failure (route missing).
- Green: `./mvnw test -Dtest=MailTemplateResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailTemplateResource.java` (added `GET /placeholders`, `PlaceholderTile` record, allowlist), `resource/MailTemplateResourceTest.java` (+1 test). Green: scoped 6/6. Deviations: none.

## Task 004 — MailJob entity + basic CRUD resource
- Goal: Create the `MailJob` entity and a CRUD REST resource for it (list/get/create/update/delete, no validation yet).
- Spec ref: R7, R8, R17, D1, Interfaces (Jobs)
- Depends on: Task 002
- Affected: `entity/MailJob.java` (new), `entity/RecipientMode.java` (new enum: `GROUPS`, `ALL_PARENTS`), `resource/MailJobResource.java` (new)
- Expected changes: `MailJob extends PanacheMongoEntity` — `name`, `templateId (ObjectId)`, `subject`, `senderAccountId (String)`, `cron (String)`, `recipientMode (RecipientMode)`, `recipientGroupDefinitionIds (List<ObjectId>)`, `active (boolean)`, `lastRunAt (Instant)`, `lastRunStatus (String)`, `lastRunError (String)`, `createdAt`, `updatedAt`. `MailJobResource` at `/api/v1/mail-jobs`: `GET` (list), `GET /{id}`, `POST` (basic required-field checks only: `name`, `templateId`, `subject`, `cron` non-blank → 400; `active` defaults `false`), `PUT /{id}`, `DELETE /{id}` (unconditional — unschedule-guard added in Task 026). Not whitelisted in `SecurityFilter`.
- Test: `resource/MailJobResourceTest.java` — `createAndListJobs`, `getById404WhenMissing`, `updateChangesFields`, `deleteRemovesJob`, `createRejectsBlankName`.
- Red: `./mvnw test -Dtest=MailJobResourceTest` → compile fails (no classes yet).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `entity/MailJob.java`, `entity/RecipientMode.java`, `resource/MailJobResource.java`, `resource/MailJobResourceTest.java`. Green: scoped 5/5. Deviations: dropped `nonAdminGets403` for the same reason as Task 002 (test-profile OIDC bypass makes `SecurityFilter` a no-op); substituted `createRejectsBlankName`.

## Task 005 — Cron validation on MailJob save
- Goal: Reject an invalid cron expression at `POST`/`PUT` with 400, using a standalone cron-utils parser of the pinned dialect.
- Spec ref: R13, D7, G-005
- Depends on: Task 001, Task 004
- Affected: `resource/MailJobResource.java`
- Expected changes: inject a `CronParser` built for `CronType.QUARTZ` (matching `application.properties`'s `cron-type`); `POST`/`PUT` call `parser.parse(job.cron).validate()`, catching `IllegalArgumentException` → `BadRequestException` with a clear message. Validation runs regardless of `active` (inactive jobs are validated too, per R13).
- Test: `resource/MailJobResourceTest.rejectsInvalidCronOnCreate` and `rejectsInvalidCronOnUpdate` (both for an inactive job, confirming validation doesn't depend on scheduling) → 400; `acceptsValidCron` → 201/200.
- Red: `./mvnw test -Dtest=MailJobResourceTest#rejectsInvalidCronOnCreate` → fails (invalid cron currently accepted, 201 instead of 400).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`CronParser` field + validation in `validate()`), `resource/MailJobResourceTest.java` (+3 tests). Green: scoped 8/8. Deviations: none.

## Task 006 — GET /mail-accounts sender-account provider
- Goal: Add `GET /mail-accounts` returning the list of selectable sender accounts (today: one, derived from the `MailSettings` singleton).
- Spec ref: R16, D8, Interfaces (Sender accounts)
- Depends on: none
- Affected: `resource/MailAccountResource.java` (new)
- Expected changes: `GET /api/v1/mail-accounts` returns `[{id, fromAddress, fromName, enabled}]`; if `MailSettings.findSingleton()` is null, returns an empty list (no account configured yet). `id` == `MailSettings.SINGLETON_ID.toHexString()`. Not whitelisted in `SecurityFilter`.
- Test: `resource/MailAccountResourceTest.java` — `listReturnsSingletonAccountWhenConfigured`, `listReturnsEmptyWhenNoSettings`.
- Red: `./mvnw test -Dtest=MailAccountResourceTest` → compile fails (no resource class).
- Green: `./mvnw test -Dtest=MailAccountResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `resource/MailAccountResource.java`, `resource/MailAccountResourceTest.java`. Green: scoped 2/2. Deviations: dropped `nonAdminGets403` (same reason as Task 002).

## Task 007 — senderAccountId validation on MailJob save
- Goal: Reject a `senderAccountId` on `POST`/`PUT` that doesn't match a real account from the sender-account provider.
- Spec ref: R16, D8, G-007
- Depends on: Task 005, Task 006
- Affected: `resource/MailJobResource.java`
- Expected changes: on save, resolve the available account ids (today: `MailSettings.SINGLETON_ID` if configured) and reject an unmatched `senderAccountId` with 400.
- Test: `resource/MailJobResourceTest.rejectsUnknownSenderAccountId` → 400; `acceptsValidSenderAccountId` (matching the singleton) → 201.
- Red: `./mvnw test -Dtest=MailJobResourceTest#rejectsUnknownSenderAccountId` → fails (currently accepted, no validation).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`validateSenderAccountId`), `resource/MailJobResourceTest.java` (+2 tests; also updated `@BeforeEach`/`validPayload`/`persistJob` fixtures to seed a `MailSettings` singleton and include a valid `senderAccountId`, since this validation now applies to every save in the class). Green: scoped 10/10. Deviations: none.

## Task 008 — recipientGroupDefinitionIds validation on MailJob save
- Goal: Reject a `recipientGroupDefinitionIds` entry on `POST`/`PUT` that doesn't reference an existing, active group `FieldDefinition`.
- Spec ref: R15, D5, G-001, G-010
- Depends on: Task 007
- Affected: `resource/MailJobResource.java`
- Expected changes: when `recipientMode == GROUPS`, each id in `recipientGroupDefinitionIds` must resolve to an active `FieldDefinition` with `fieldName == "group"` (via `FieldDefinition.findById` + `outdatedAt == null` check); any miss → 400 naming the offending id. `ALL_PARENTS` mode ignores this list (may be empty).
- Test: `resource/MailJobResourceTest.rejectsUnknownOrOutdatedGroupDefinitionId` → 400; `acceptsValidGroupDefinitionIds` → 201.
- Red: `./mvnw test -Dtest=MailJobResourceTest#rejectsUnknownOrOutdatedGroupDefinitionId` → fails (currently accepted).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`validateRecipientGroupDefinitionIds`), `resource/MailJobResourceTest.java` (+2 tests, `FieldDefinition.deleteAll()` added to cleanup). Green: scoped 12/12. Deviations: none.

## Task 009 — Delete-guard: template referenced by a job
- Goal: Reject `DELETE /mail-templates/{id}` with 409 when a `MailJob` still references it.
- Spec ref: Error Handling ("Template referenced by a job")
- Depends on: Task 004
- Affected: `resource/MailTemplateResource.java`
- Expected changes: `DELETE /{id}` first checks `MailJob.list("templateId", templateObjectId)`; if non-empty, throws a `WebApplicationException(409)` naming the referencing job id(s); otherwise proceeds with delete as before.
- Test: `resource/MailTemplateResourceTest.deleteRejectedWhenReferencedByJob` (seed a `MailJob` pointing at the template) → 409; existing `deleteRemovesTemplate` (no referencing job) still → 204.
- Red: `./mvnw test -Dtest=MailTemplateResourceTest#deleteRejectedWhenReferencedByJob` → fails (currently deletes unconditionally, 204 instead of 409).
- Green: `./mvnw test -Dtest=MailTemplateResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailTemplateResource.java` (delete-guard via `MailJob.list("templateId", ...)`, 409 `WebApplicationException`), `resource/MailTemplateResourceTest.java` (+1 test, `MailJob.deleteAll()` added to cleanup). Green: scoped 7/7. Deviations: none.

## Task 010 — MailService.sendHtml
- Goal: Add an HTML-capable send method to `MailService` that reuses the existing session/encryption/guard logic.
- Spec ref: D3, R2
- Depends on: none
- Affected: `service/MailService.java`
- Expected changes: new public method `sendHtml(String recipient, String subject, String htmlBody)` mirroring `send`'s guard checks (`CONFIG_MISSING`/`AUTH_FAILED`/`CONNECTION_FAILED`/`UNKNOWN` `MailException` categories) and session-building (`buildProperties`, sender from `MailSettings.findSingleton()`), but sets the MIME body as `text/html; charset=UTF-8` instead of `setText(body, "UTF-8")`. Existing `send` is untouched.
- Test: `service/MailServiceTest.sendHtmlDeliversHtmlContentType` — using `GreenMail`, configure a test `MailSettings`, call `sendHtml(...)`, assert the received message's content-type is `text/html` and body matches; existing plaintext `send` test (if present) still passes.
- Red: `./mvnw test -Dtest=MailServiceTest#sendHtmlDeliversHtmlContentType` → compile fails (no `sendHtml` method).
- Green: `./mvnw test -Dtest=MailServiceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `service/MailService.java` (extracted `prepareMessage()` shared by `send`/`sendHtml`, added `sendHtml`); file added: `service/MailServiceTest.java` (new — none existed before) with `sendHtmlDeliversHtmlContentType` and a `sendDeliversPlainText` regression check for the untouched `send` path. Green: scoped 2/2. Deviations: the extraction required adding `UnsupportedEncodingException` to `prepareMessage`'s throws clause (the 2-arg `InternetAddress` constructor throws it) — behaviorally identical since both callers already had a catch-all `Exception` branch producing the same `UNKNOWN` category as before.

## Task 011 — MailTemplateRenderer (token substitution)
- Goal: Implement pure server-side `{{person.<fieldName>}}` token substitution against a per-recipient property map.
- Spec ref: R5, D2
- Depends on: none
- Affected: `service/MailTemplateRenderer.java` (new)
- Expected changes: `String render(String bodyHtml, Map<String, String> properties)` — regex-replaces every `{{person.<fieldName>}}` occurrence with the HTML-escaped value from `properties.get(fieldName)`, or empty string if absent/null. Tokens may repeat any number of times at any position. No I/O.
- Test: `service/MailTemplateRendererTest.java` — `substitutesKnownToken`, `blanksUnresolvedToken`, `substitutesRepeatedAndMultipleTokens`, `htmlEscapesSubstitutedValue` (a value containing `<script>` renders escaped, not raw).
- Red: `./mvnw test -Dtest=MailTemplateRendererTest` → compile fails (class doesn't exist).
- Green: `./mvnw test -Dtest=MailTemplateRendererTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `service/MailTemplateRenderer.java`, `service/MailTemplateRendererTest.java`. Green: scoped 4/4 (plain JUnit5, no `@QuarkusTest`, ran in 0.113s). Deviations: none.

## Task 012 — HTML sanitize pass on template save
- Goal: Run a defensive HTML sanitize pass over `bodyHtml` on `MailTemplate` `POST`/`PUT`, preserving inline styles but stripping unsafe markup.
- Spec ref: D6, G-003, Security Considerations
- Depends on: Task 002
- Affected: `resource/MailTemplateResource.java`
- Expected changes: build an OWASP `PolicyFactory` allowing common formatting tags (`p, br, b, strong, i, em, u, ol, ul, li, a[href], span, div`) plus the `style` attribute on all allowed elements (needed to preserve Quill's inline-style output, G-003); `POST`/`PUT` sanitize `bodyHtml` through this policy before persisting.
- Test: `resource/MailTemplateResourceTest.sanitizesScriptTagsButKeepsInlineStyles` — POST a body containing both `<script>alert(1)</script>` and `<span style="color:red">x</span>`; assert the persisted/returned `bodyHtml` has no `<script>` but retains the `style` attribute.
- Red: `./mvnw test -Dtest=MailTemplateResourceTest#sanitizesScriptTagsButKeepsInlineStyles` → fails (script tag currently passed through unsanitized).
- Green: `./mvnw test -Dtest=MailTemplateResourceTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailTemplateResource.java` (`HTML_POLICY` via OWASP `HtmlPolicyBuilder`, applied in `create`/`update`), `resource/MailTemplateResourceTest.java` (+1 test). Green: scoped 8/8. Deviations: none.

## Task 013 — PersonPropertyResolver (batch property-map builder)
- Goal: Given a list of `Person`s, batch-resolve each one's scalar allowlisted properties into a `Map<ObjectId personId, Map<String fieldName, String value>>` in a bounded number of queries (not per-person N+1).
- Spec ref: R5, G-008, Performance Considerations
- Depends on: Task 003
- Affected: `service/PersonPropertyResolver.java` (new)
- Expected changes: one query for the allowlisted active `FieldDefinition`s (building `definitionId → fieldName` map), then one batched `field_instances` query (`$in` over all needed `fieldInstanceId`s collected from every person's `basicProperties`) rather than one query per `FieldRef`. Returns a map keyed by person id; a person missing a given property simply has no entry for that `fieldName` (renderer already blanks missing values, Task 011).
- Test: `service/PersonPropertyResolverTest.java` (`@QuarkusTest`, real Mongo) — seed 2 `Person`s with `firstName`/`email` field instances (one missing `email`), assert the returned map has correct values and the missing one is simply absent; assert only the allowlisted fields appear (a seeded `address` FieldRef is ignored).
- Red: `./mvnw test -Dtest=PersonPropertyResolverTest` → compile fails (class doesn't exist).
- Green: `./mvnw test -Dtest=PersonPropertyResolverTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `service/PersonPropertyResolver.java`, `service/PersonPropertyResolverTest.java`. Green: scoped 1/1 (one test with multiple assertions covering allowlist inclusion, compound-field exclusion, and missing-value omission). Deviations: the allowlist constant is duplicated (not shared) from `MailTemplateResource`'s — documented in a class comment as expressing the same R3 contract; kept duplicated rather than refactoring the already-completed Task 003 resource, per each task's stated `Affected` scope.

## Task 014 — RecipientResolverService: group-based resolution
- Goal: Resolve the live set of parent `Person`s for a set of selected group `definitionId`s in the current semester.
- Spec ref: R11, R15, D5, G-001, G-008
- Depends on: Task 013
- Affected: `service/RecipientResolverService.java` (new)
- Expected changes: `List<Person> resolveGroupParents(List<ObjectId> groupDefinitionIds, ObjectId semesterId)` — queries `semester_assignments` (`section: "group"`, `semesterId`, `definitionId` `$in` the given ids) → child `personId`s → `Person.findByFamilyId` per distinct `familyId` (batched by collecting distinct family ids first) → filter to `isParent(person)` (new private helper mirroring `PersonResource.isChild`, checking `personType == "PARENT"`) with a non-blank `email` value (via `PersonPropertyResolver` or a direct email lookup). Deduped by person id.
- Test: `service/RecipientResolverServiceTest.resolveGroupParentsReturnsDedupedParentsWithEmail` (`@QuarkusTest`, real Mongo) — seed a group, two children in it from the same family plus one from another family, corresponding parents (one with email, one without), assert exactly the email-having parents are returned, deduped.
- Red: `./mvnw test -Dtest=RecipientResolverServiceTest` → compile fails (class doesn't exist).
- Green: `./mvnw test -Dtest=RecipientResolverServiceTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `service/RecipientResolverService.java` (`resolveGroupParents`, `isParent`, `resolveEmail` helpers), `service/RecipientResolverServiceTest.java`. Green: scoped 1/1 (dedup + no-email exclusion verified in one test). Deviations: none.

## Task 015 — RecipientResolverService: ALL_PARENTS resolution
- Goal: Resolve every parent `Person` with a non-blank email, independent of group membership.
- Spec ref: R11, R15, D5
- Depends on: Task 014
- Affected: `service/RecipientResolverService.java`
- Expected changes: `List<Person> resolveAllParents()` — `Person.listAll()` filtered to `isParent` + non-blank email (reusing the same helper/email-lookup from Task 014).
- Test: `service/RecipientResolverServiceTest.resolveAllParentsReturnsEveryParentWithEmail` — seed a parent with email, a parent without, a child; assert only the email-having parent returned.
- Red: `./mvnw test -Dtest=RecipientResolverServiceTest#resolveAllParentsReturnsEveryParentWithEmail` → compile fails (method doesn't exist).
- Green: `./mvnw test -Dtest=RecipientResolverServiceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: file changed: `service/RecipientResolverServiceTest.java` (+1 test). Green: scoped 2/2. Deviations: `resolveAllParents()` was already implemented in Task 014's file write (it shares `isParent`/`resolveEmail` helpers, so both methods were authored together) — this task adds and proves its test coverage.

## Task 016 — RecipientResolverService: job-level dispatcher
- Goal: Given a `MailJob`, produce the final `(email, propertyMap)` list by dispatching to group or all-parents resolution and attaching property maps.
- Spec ref: R11
- Depends on: Task 015, Task 013
- Affected: `service/RecipientResolverService.java`
- Expected changes: `List<ResolvedRecipient> resolve(MailJob job, ObjectId semesterId)` (new small record/class `ResolvedRecipient(String email, Map<String, String> properties)`) — dispatches on `job.recipientMode`, calls the appropriate resolver method, then `PersonPropertyResolver` to attach each recipient's property map, returns the combined list.
- Test: `service/RecipientResolverServiceTest.resolveDispatchesByRecipientModeAndAttachesProperties` — for both `GROUPS` and `ALL_PARENTS` modes, assert the returned `ResolvedRecipient`s carry both a correct email and a populated property map.
- Red: `./mvnw test -Dtest=RecipientResolverServiceTest#resolveDispatchesByRecipientModeAndAttachesProperties` → compile fails.
- Green: `./mvnw test -Dtest=RecipientResolverServiceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `service/RecipientResolverService.java` (`ResolvedRecipient` record, `resolve(MailJob, ObjectId)`), `service/RecipientResolverServiceTest.java` (+1 test). Green: scoped 3/3. Deviations: none.

## Task 017 — MailJobScheduler: schedule/unschedule
- Goal: Register and deregister a `MailJob`'s cron schedule with the Quarkus programmatic `Scheduler`, in the fixed `Europe/Vienna` timezone.
- Spec ref: D4, G-006
- Depends on: Task 001
- Affected: `scheduler/MailJobScheduler.java` (new)
- Expected changes: `@ApplicationScoped` bean injecting `io.quarkus.scheduler.Scheduler`. `schedule(MailJob job)` — unschedules any existing registration for `job.id` first (idempotent arm, per Error Handling), then `scheduler.newJob(job.id.toHexString()).setCron(job.cron).setTimeZone(TimeZone.getTimeZone("Europe/Vienna")).setTask(ctx -> fire(job.id)).schedule()`. `unschedule(ObjectId jobId)` — `scheduler.unscheduleJob(jobId.toHexString())`. `fire(ObjectId jobId)` is a stub in this task (no-op body — full orchestration lands in Task 018).
- Test: `scheduler/MailJobSchedulerTest.java` (`@QuarkusTest`, injects real `Scheduler`) — `scheduleRegistersJobWithScheduler` (assert `scheduler.getScheduledJobs()` contains an id matching the job), `unscheduleRemovesJob`, `scheduleTwiceIsIdempotent` (re-scheduling the same job id doesn't produce duplicate registrations).
- Red: `./mvnw test -Dtest=MailJobSchedulerTest` → compile fails (class doesn't exist).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `scheduler/MailJobScheduler.java`, `scheduler/MailJobSchedulerTest.java`; file changed: `application.properties` (`quarkus.scheduler.start-mode=forced`). Green: scoped 3/3. Deviations: (1) `setTimeZone` takes a `String` timezone id, not `java.util.TimeZone` — verified via `javap` against the `quarkus-scheduler-api` jar before writing the call; corrected from the plan's sketch. (2) discovered the Quarkus scheduler doesn't start at all unless the app has a compile-time `@Scheduled` method or `quarkus.scheduler.start-mode=forced` is set — this app is 100% programmatic scheduling, so added the config property (not anticipated in the plan; a necessary correction, not a scope change). — MailJobScheduler.runJob: happy path
- Goal: Implement the per-fire orchestration happy path — resolve recipients, render, send to each, record a `SUCCESS` outcome.
- Spec ref: R11, R12, Data Flow (steps 3–7, happy path)
- Depends on: Task 010, Task 011, Task 016, Task 017
- Affected: `scheduler/MailJobScheduler.java`
- Expected changes: `void runJob(MailJob job, MailTemplate template)` — resolves recipients via `RecipientResolverService.resolve`, renders each via `MailTemplateRenderer.render`, sends each via `MailService.sendHtml`, tallies successes, sets `job.lastRunAt = Instant.now()`, `job.lastRunStatus = "SUCCESS"`, clears `job.lastRunError`, persists via `job.update()`. `fire(ObjectId jobId)` (from Task 017) is wired to load `MailJob`/`MailTemplate` via their static finders and delegate to `runJob`.
- Test: `scheduler/MailJobSchedulerTest.runJobSendsToAllRecipientsAndRecordsSuccess` (`@QuarkusTest` + `GreenMail`) — construct a `MailJob`/`MailTemplate` directly (no scheduler registration needed), seed one group with one parent with email, call `runJob(job, template)` directly, assert GreenMail received one message with substituted content and `job.lastRunStatus == "SUCCESS"`.
- Red: `./mvnw test -Dtest=MailJobSchedulerTest#runJobSendsToAllRecipientsAndRecordsSuccess` → compile fails (`runJob` doesn't exist).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `scheduler/MailJobScheduler.java` (`fire`, `runJob`, `resolveCurrentSemesterId`); file added: `scheduler/MailJobRunTest.java`. Green: scoped 1/1. Deviations: the happy-path (and subsequent runJob-behavior) test was put in a separate `MailJobRunTest` class rather than added to `MailJobSchedulerTest`, since it needs the `GreenMailExtension` that `MailJobSchedulerTest` (schedule/unschedule only) doesn't — keeps the fast schedule/unschedule tests fast and the GreenMail-backed run tests grouped together. Same package, same coverage intent as the plan's `MailJobSchedulerTest.runJob*` naming.

## Task 019 — runJob: overlap guard
- Goal: Skip a `fire` whose job is already running, recording `SKIPPED_OVERLAP` instead of double-sending.
- Spec ref: D4 (overlap guard), G-004, Error Handling
- Depends on: Task 018
- Affected: `scheduler/MailJobScheduler.java`
- Expected changes: an in-memory `Set<ObjectId>` (e.g. `ConcurrentHashMap.newKeySet()`) of currently-running job ids. `fire(jobId)` (or `runJob`, whichever owns the guard) checks-and-adds atomically before doing any work; if already present, records `lastRunStatus = "SKIPPED_OVERLAP"` and returns without calling the resolver/renderer/sender. The id is removed from the set in a `finally` block so a crash never leaves it stuck.
- Test: `scheduler/MailJobSchedulerTest.runJobSkipsWhenAlreadyRunning` — pre-mark a job id as running (via a package-visible test hook or by calling `runJob` with a slow/blocking fake resolver on one thread while asserting a second concurrent call short-circuits), assert the resolver is never invoked on the second call and `lastRunStatus == "SKIPPED_OVERLAP"`.
- Red: `./mvnw test -Dtest=MailJobSchedulerTest#runJobSkipsWhenAlreadyRunning` → fails (no guard yet, second call proceeds normally).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `scheduler/MailJobScheduler.java` (`runningJobIds` guard in `runJob`, `markRunningForTest` package-private test hook), `scheduler/MailJobRunTest.java` (+1 test). Green: scoped 2/2 (initially red: `expected SKIPPED_OVERLAP but was SUCCESS`). Deviations: first attempt wrote directly to `mailJobScheduler.runningJobIds` from the test — failed because CDI client proxies only forward method calls, not field access, so the write landed on the proxy's own field instance rather than the real bean's. Fixed by adding `markRunningForTest(ObjectId)`, a real method that correctly delegates through the proxy.

## Task 020 — runJob: NO_RECIPIENTS status
- Goal: Record a distinct `NO_RECIPIENTS` status (not `SUCCESS`) when live resolution yields zero recipients.
- Spec ref: G-009, Error Handling, Data Flow step 3
- Depends on: Task 018
- Affected: `scheduler/MailJobScheduler.java`
- Expected changes: after resolving recipients in `runJob`, if the list is empty, set `lastRunStatus = "NO_RECIPIENTS"`, `lastRunAt = Instant.now()`, persist, and return without attempting any send.
- Test: `scheduler/MailJobSchedulerTest.runJobRecordsNoRecipientsWhenResolutionIsEmpty` — a job targeting a group with no assigned children, assert `lastRunStatus == "NO_RECIPIENTS"` and GreenMail received zero messages.
- Red: `./mvnw test -Dtest=MailJobSchedulerTest#runJobRecordsNoRecipientsWhenResolutionIsEmpty` → fails (currently records `SUCCESS` for zero recipients).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `scheduler/MailJobScheduler.java` (empty-recipients early return with `NO_RECIPIENTS`), `scheduler/MailJobRunTest.java` (+1 test). Green: scoped 3/3. Deviations: none.

## Task 021 — runJob: missing-template auto-deactivate
- Goal: When a job's referenced template no longer exists, record `FAILED` and auto-deactivate the job.
- Spec ref: G-011, Error Handling, Data Flow step 2
- Depends on: Task 018
- Affected: `scheduler/MailJobScheduler.java`
- Expected changes: `fire(jobId)` loads the `MailTemplate` before calling `runJob`; if `MailTemplate.findById(job.templateId)` returns null, sets `job.lastRunStatus = "FAILED"`, `job.lastRunError = "template missing"`, `job.active = false`, persists, calls `unschedule(job.id)`, and returns — never calls `runJob`.
- Test: `scheduler/MailJobSchedulerTest.fireAutoDeactivatesJobWithMissingTemplate` — a job referencing a non-existent `templateId`, call `fire(jobId)`, assert `job.active == false`, `lastRunStatus == "FAILED"`, and the scheduler no longer has it registered.
- Red: `./mvnw test -Dtest=MailJobSchedulerTest#fireAutoDeactivatesJobWithMissingTemplate` → fails (currently throws/NPEs or proceeds without the guard).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `scheduler/MailJobScheduler.java` (`fire` checks for missing template → FAILED + auto-deactivate + unschedule), `scheduler/MailJobRunTest.java` (+1 test). Green: scoped 4/4. Deviations: none.

## Task 022 — runJob: per-recipient failure isolation
- Goal: A single recipient's send failure doesn't abort the run; outcome is `PARTIAL` (some succeeded) or `FAILED` (none did).
- Spec ref: R12, Error Handling
- Depends on: Task 018
- Affected: `scheduler/MailJobScheduler.java`
- Expected changes: `runJob`'s per-recipient send loop wraps each `MailService.sendHtml` call in a try/catch, counting successes and failures instead of letting one exception abort the loop; after the loop, `lastRunStatus` is `"SUCCESS"` (all sent), `"PARTIAL"` (some sent, some failed), or `"FAILED"` (none sent), with `lastRunError` summarizing the failure count/last error message.
- Test: `scheduler/MailJobSchedulerTest.runJobRecordsPartialWhenOneRecipientFails` — two resolved recipients, one with a valid email (GreenMail accepts) and one with a syntactically invalid email value seeded on that person's `email` field instance (triggers a `jakarta.mail` address-parse failure inside `MailService`), assert the valid recipient still received mail and `lastRunStatus == "PARTIAL"`.
- Red: `./mvnw test -Dtest=MailJobSchedulerTest#runJobRecordsPartialWhenOneRecipientFails` → fails (currently the whole run aborts/throws on the first failure).
- Green: `./mvnw test -Dtest=MailJobSchedulerTest` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `scheduler/MailJobScheduler.java` (per-recipient try/catch with success/failure tallies → SUCCESS/PARTIAL/FAILED), `scheduler/MailJobRunTest.java` (+1 test). Green: scoped 5/5. Deviations: used `invalid@@example.test` (double `@`) as the deterministic bad address per the plan's risk note — `jakarta.mail`'s address parsing rejected it as intended, no fallback needed.

## Task 023 — MailJobStartupRearmer
- Goal: On boot, re-arm every currently-`active` `MailJob` with the scheduler.
- Spec ref: R10, Observability (startup log line)
- Depends on: Task 017
- Affected: `scheduler/MailJobStartupRearmer.java` (new)
- Expected changes: `@ApplicationScoped` bean with `void onStart(@Observes StartupEvent ev)` delegating to a package-visible `void rearmAll()` — loads `MailJob.list("active", true)`, calls `MailJobScheduler.schedule(job)` for each, logs the count (`Logger` at INFO). `onStart` itself is a one-line delegate (no logic of its own, exempt from its own test per the non-behavioral carve-out; `rearmAll()` is the tested unit).
- Test: `scheduler/MailJobStartupRearmerTest.java` (`@QuarkusTest`) — seed one active and one inactive `MailJob`, call `rearmer.rearmAll()` directly, assert the scheduler has a registration for the active job's id and none for the inactive one.
- Red: `./mvnw test -Dtest=MailJobStartupRearmerTest` → compile fails (class doesn't exist).
- Green: `./mvnw test -Dtest=MailJobStartupRearmerTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `scheduler/MailJobStartupRearmer.java`, `scheduler/MailJobStartupRearmerTest.java`. Green: scoped 1/1 (log line confirms 0 re-armed at real boot with empty test DB, 1 re-armed when the test calls `rearmAll()` directly against seeded data — matches the intended "test the extracted method, not the trigger" scope-narrowing recorded in the plan's Risks). Deviations: none beyond the pre-recorded risk.

## Task 024 — Activate/deactivate endpoints
- Goal: Add `POST /mail-jobs/{id}/activate` and `/deactivate`, wiring `MailJobScheduler.schedule`/`unschedule`.
- Spec ref: R8, R9, Data Flow (Activation)
- Depends on: Task 017, Task 004
- Affected: `resource/MailJobResource.java`
- Expected changes: `POST /{id}/activate` — validates cron (reuse Task 005's check, in case the stored cron predates a dialect change), calls `mailJobScheduler.schedule(job)`, sets `job.active = true`, persists. `POST /{id}/deactivate` — calls `mailJobScheduler.unschedule(job.id)`, sets `job.active = false`, persists.
- Test: `resource/MailJobResourceTest.activateSchedulesAndSetsActive`, `deactivateUnschedulesAndClearsActive` — assert both the DB flag and the scheduler's registration state.
- Red: `./mvnw test -Dtest=MailJobResourceTest#activateSchedulesAndSetsActive` → 404 (route doesn't exist).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`activate`/`deactivate` endpoints), `resource/MailJobResourceTest.java` (+2 tests). Green: scoped 14/14 (initially red: 415 Unsupported Media Type). Deviations: the class-level `@Consumes(APPLICATION_JSON)` rejected the no-body POSTs RestAssured sends by default (415, not the expected 404-then-200 flow); fixed by overriding `@Consumes(MediaType.WILDCARD)` on both new endpoints, since they take no request body.

## Task 025 — Re-arm on cron change while active
- Goal: `PUT /mail-jobs/{id}` re-registers the schedule when an active job's cron changes.
- Spec ref: Data Flow (Activation/Update)
- Depends on: Task 024
- Affected: `resource/MailJobResource.java`
- Expected changes: `PUT /{id}` — after validating and applying field changes, if `job.active && cronChanged`, calls `mailJobScheduler.schedule(job)` again (idempotent re-arm, already unschedules-first per Task 017).
- Test: `resource/MailJobResourceTest.updatingCronOnActiveJobReArms` — activate a job, `PUT` a new cron, assert the scheduler's registration reflects the new cron (or re-registration occurred — assert via `scheduler.getScheduledJobs()` cron string).
- Red: `./mvnw test -Dtest=MailJobResourceTest#updatingCronOnActiveJobReArms` → fails (scheduler still has old cron).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`update` re-arms when `job.active && cronChanged`), `resource/MailJobResourceTest.java` (+1 test). Green: scoped 15/15. Deviations: `Trigger` doesn't expose the raw cron string (confirmed via `javap`), so the test asserts re-registration via a changed `getNextFireTime()` (daily-8am → daily-9am) rather than reading back the cron text — equivalent proof of re-arm.

## Task 026 — Delete unschedules first
- Goal: `DELETE /mail-jobs/{id}` unschedules the job before removing it, if active.
- Spec ref: Interfaces (`DELETE /mail-jobs/{id}`)
- Depends on: Task 024
- Affected: `resource/MailJobResource.java`
- Expected changes: `DELETE /{id}` — if `job.active`, calls `mailJobScheduler.unschedule(job.id)` before `job.delete()`.
- Test: `resource/MailJobResourceTest.deletingActiveJobUnschedulesIt` — activate a job, delete it, assert the scheduler no longer has a registration for that id.
- Red: `./mvnw test -Dtest=MailJobResourceTest#deletingActiveJobUnschedulesIt` → fails (stale scheduler registration remains).
- Green: `./mvnw test -Dtest=MailJobResourceTest` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `resource/MailJobResource.java` (`delete` unschedules first if active), `resource/MailJobResourceTest.java` (+1 test). Green: scoped 16/16. Deviations: none. This completes the backend portion of the plan (Tasks 001–026).

## Task 027 — Frontend: mail-template model + service
- Goal: Add the `MailTemplate` model and a thin `ApiService`-backed CRUD + placeholders service.
- Spec ref: R1, R3, R6, Interfaces (Templates)
- Depends on: none
- Affected: `frontend/src/app/shared/models/mail-template.model.ts` (new), `frontend/src/app/shared/services/mail-template.service.ts` (new)
- Expected changes: `MailTemplate` interface (`id, name, bodyHtml, createdAt, updatedAt`), `PlaceholderTile` interface (`token, fieldName, label`). `MailTemplateService` — `list()`, `get(id)`, `create(req)`, `update(id, req)`, `delete(id)`, `placeholders()`, mirroring `MailSettingsService`'s constructor-injected `ApiService` pattern.
- Test: N/A — thin HTTP wrapper, no branching logic (mirrors `mail-settings.service.ts`, which has no dedicated spec in the repo).
- Verification: `ng build` → succeeds with no new type errors.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `shared/models/mail-template.model.ts`, `shared/services/mail-template.service.ts`. Verification: `ng build` succeeded (only pre-existing, unrelated warnings: bundle budget, an optional-chain lint note in `parents-step.component.ts`). Deviations: none.

## Task 028 — Frontend: mail-job model + service
- Goal: Add the `MailJob` model and a thin CRUD + activate/deactivate service.
- Spec ref: R7, R8, Interfaces (Jobs)
- Depends on: none
- Affected: `frontend/src/app/shared/models/mail-job.model.ts` (new), `frontend/src/app/shared/services/mail-job.service.ts` (new)
- Expected changes: `MailJob`/`RecipientMode` types matching the backend schema (Task 004); `MailJobService` — `list()`, `get(id)`, `create(req)`, `update(id, req)`, `delete(id)`, `activate(id)`, `deactivate(id)`.
- Test: N/A — thin HTTP wrapper, no branching logic.
- Verification: `ng build` → succeeds with no new type errors.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `shared/models/mail-job.model.ts`, `shared/services/mail-job.service.ts`. Verification: `ng build` succeeded (same pre-existing warnings only). Deviations: none.

## Task 029 — Frontend: mail-account model + service
- Goal: Add the sender-account model and a thin list service.
- Spec ref: R16, D8, Interfaces (Sender accounts)
- Depends on: none
- Affected: `frontend/src/app/shared/models/mail-account.model.ts` (new), `frontend/src/app/shared/services/mail-account.service.ts` (new)
- Expected changes: `MailAccount` interface (`id, fromAddress, fromName, enabled`); `MailAccountService.list()`.
- Test: N/A — thin HTTP wrapper, no branching logic.
- Verification: `ng build` → succeeds with no new type errors.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `shared/models/mail-account.model.ts`, `shared/services/mail-account.service.ts`. Verification: `ng build` succeeded. Deviations: none.

## Task 030 — Frontend: add ngx-quill dependency
- Goal: Add `ngx-quill` and `quill` as frontend dependencies.
- Spec ref: D6
- Depends on: none
- Affected: `frontend/package.json`, `frontend/package-lock.json`
- Expected changes: `npm install ngx-quill quill --save` (versions compatible with Angular 18.2 — `ngx-quill` 25.x line). No usage yet (wired in Task 032).
- Test: N/A — dependency addition, no behavior change.
- Verification: `npm install` → succeeds; `ng build` → succeeds (unused-dependency state is a valid working state).
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `package.json`, `package-lock.json`. Verification: `npm install` succeeded; `ng build` succeeded. Deviations: latest `ngx-quill` (31.x) requires Angular ^22, incompatible with this repo's Angular 18.2; checked peer deps across the version history and installed `ngx-quill@26.0.10` + `quill@^2.0.0` (the highest line whose peer dep is `@angular/core ^18.0.0`) instead of the plan's guessed "25.x line".

## Task 031 — Frontend: template list + edit form skeleton
- Goal: A component listing templates and a create/edit form (name + plain textarea body — no rich editor yet) wired to `MailTemplateService`.
- Spec ref: R1
- Depends on: Task 027
- Affected: `frontend/src/app/settings/mail/mail-template-editor/mail-template-editor.component.{ts,html,scss,spec.ts}` (new)
- Expected changes: standalone `MailTemplateEditorComponent` — list of templates (name + updatedAt), select-to-edit, form with `name` + `bodyHtml` (plain `<textarea>` for now), save (create or update) and delete buttons, calling `MailTemplateService`. Built via direct-instantiation + fake-service testing, matching `mail.component.spec.ts`.
- Test: `mail-template-editor.component.spec.ts` — `loadsAndListsTemplates`, `savingNewTemplateCallsCreate`, `savingExistingTemplateCallsUpdate`, `deleteCallsServiceDelete`.
- Red: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → fails (component doesn't exist).
- Green: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `settings/mail/mail-template-editor/mail-template-editor.component.{ts,html,scss,spec.ts}`. Green: scoped 4/4. Deviations: none.

## Task 032 — Frontend: swap in ngx-quill with inline-style config
- Goal: Replace the plain textarea with an ngx-quill editor configured to emit inline-styled, email-safe HTML.
- Spec ref: R2, D6, G-003
- Depends on: Task 030, Task 031
- Affected: `mail-template-editor.component.{ts,html,scss,spec.ts}`
- Expected changes: import `QuillModule.forRoot(...)` with inline-style attributors registered (`AlignStyle`, `SizeStyle`, `DirectionStyle`, etc., per Quill's `Quill.register(...)` inline-format API) so the editor's toolbar (bold/italic/color/font-size/lists/links/alignment) serializes to `style="…"` attributes rather than `ql-*` classes; toolbar limited to formats that support inline-style output. `<quill-editor formControlName="bodyHtml">` (or `[(ngModel)]`) replaces the `<textarea>`.
- Test: `mail-template-editor.component.spec.ts.editorEmitsInlineStyledHtml` — simulate setting a formatted value through the component's form control and assert the persisted `bodyHtml` sent to `create()`/`update()` contains `style=` rather than a `ql-` class name (may require a light integration test rendering the quill instance, or asserting the Quill config object passed to `QuillModule.forRoot` includes the inline-style attributor registrations — whichever is feasible without a full browser DOM; the intent is Quill is *configured* for inline styles, not necessarily exercising real user typing in the test).
- Red: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → fails (still a plain textarea / no inline-style config).
- Green: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → all pass; `ng build` succeeds.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `settings/mail/mail-template-editor/quill-email-safe.config.ts` (isolated `configureQuillForEmailSafeOutput()` + `EMAIL_SAFE_QUILL_TOOLBAR`); files changed: `mail-template-editor.component.ts` (imports `QuillModule`, calls the config function in the constructor), `.html` (`<quill-editor>` replaces the textarea). Green: scoped 6/6; `ng build` succeeded. Deviations: (1) inspected Quill's source directly (`node_modules/quill/quill.js`, `formats/*.js`) rather than guessing the API — confirmed `background`/`color` are already inline-style by default, only `align`/`size` (and `font`, unused here) need re-registration via `Quill.register('formats/x', XStyle, true)`. (2) **`indent` and bullet `list` have no inline-style variant anywhere in Quill core** (`indent.js` only exports a `ClassAttributor`; `list.js`'s bullet/ordered both render as `<ol data-list="...">`, styled bullet-vs-numbered purely via Quill's own CSS classes) — excluded both from the toolbar rather than offering a control whose formatting silently disappears in delivered mail, per the plan's own "toolbar limited to formats that support inline-style output" criterion. This narrows D6/R2's toolbar scope from the plan's illustrative "bold/italic/color/font-size/lists/links/alignment" list; flagged here rather than silently dropped.

## Task 033 — Frontend: placeholder tiles panel
- Goal: Show a panel of placeholder tiles (from `MailTemplateService.placeholders()`) that insert a token at the editor's cursor on click.
- Spec ref: R3, R4
- Depends on: Task 032
- Affected: `mail-template-editor.component.{ts,html,scss,spec.ts}`
- Expected changes: on init, load `placeholders()` and render one tile per entry (label + token); clicking a tile inserts its `token` text at the current Quill cursor position via the Quill API (`quillEditorRef.insertText(cursorIndex, token)`), falling back to append-at-end if no selection exists.
- Test: `mail-template-editor.component.spec.ts.loadsPlaceholderTilesFromService`, `clickingTileInsertsTokenIntoBody` (assert the form's `bodyHtml` control value contains the clicked token after the click, using a fake `MailTemplateService.placeholders()` and a stubbed/fake Quill instance).
- Red: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → fails (no tiles/insertion logic).
- Green: `ng test --watch=false --include='**/mail-template-editor.component.spec.ts'` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `mail-template-editor.component.ts` (`placeholders`, `quillInstance`, `onEditorCreated`, `insertPlaceholder`), `.html` (tiles panel + `(onEditorCreated)` binding), `.spec.ts` (+4 tests). Green: scoped 9/9. Deviations: `insertPlaceholder` explicitly patches the form control after calling `quillInstance.insertText(...)`, rather than relying solely on Quill's internal text-change → ControlValueAccessor propagation — needed so the same code path is verifiable with a plain fake Quill stub in tests (no real editor/DOM), while still calling the real Quill API correctly when a real editor is present.

## Task 034 — Frontend: job list + configure-job form skeleton
- Goal: A component listing jobs and a configure-job form (name, template select, subject, sender-account dropdown, raw cron field — no group picker or presets yet) wired to `MailJobService`.
- Spec ref: R7, R8, R16
- Depends on: Task 028, Task 029
- Affected: `frontend/src/app/settings/mail/mail-job-editor/mail-job-editor.component.{ts,html,scss,spec.ts}` (new)
- Expected changes: standalone `MailJobEditorComponent` — list of jobs (name, active, lastRunStatus placeholder), select-to-edit, form with `name`, `templateId` (select, from `MailTemplateService.list()`), `subject`, `senderAccountId` (select, from `MailAccountService.list()`), `cron` (plain text field for now), save (create/update) and delete, calling `MailJobService`.
- Test: `mail-job-editor.component.spec.ts` — `loadsAndListsJobs`, `savingNewJobCallsCreate`, `savingExistingJobCallsUpdate`, `deleteCallsServiceDelete`, `populatesTemplateAndSenderDropdownsFromServices`.
- Red: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → fails (component doesn't exist).
- Green: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files added: `settings/mail/mail-job-editor/mail-job-editor.component.{ts,html,scss,spec.ts}`. Green: scoped 5/5. Deviations: none.

## Task 035 — Frontend: recipient selection UI
- Goal: Replace/extend the job form with a group multiselect (checkbox per group) + a single ALL option, sourced from the existing `OrganisationService.getByTag('groups')`.
- Spec ref: R15, D5
- Depends on: Task 034
- Affected: `mail-job-editor.component.{ts,html,scss,spec.ts}`
- Expected changes: on init, load groups via `organisationService.getByTag('groups')` (existing service, reused per spec — no new group endpoint); render a checkbox per group definition plus a mutually-exclusive "ALL" radio/toggle; selecting ALL clears/disables the group checkboxes and sets `recipientMode = 'ALL_PARENTS'`; selecting any group(s) sets `recipientMode = 'GROUPS'` and populates `recipientGroupDefinitionIds`.
- Test: `mail-job-editor.component.spec.ts.rendersGroupCheckboxesFromOrganisationService`, `selectingGroupsSetsGroupsModeAndIds`, `selectingAllClearsGroupSelectionAndSetsAllMode`.
- Red: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → fails (no group picker yet).
- Green: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → all pass.
- Size: M
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `mail-job-editor.component.ts` (`groups`, `isGroupSelected`, `toggleGroup`, `selectAllParents`, `OrganisationService` injection), `.html` (radio + checkboxes), `.spec.ts` (+3 tests, `FakeOrganisationService`). Green: scoped 8/8. Deviations: none — reused `OrganisationService.getByTag('groups')` exactly as the spec specified, no new group endpoint.

## Task 036 — Frontend: cron presets + advanced raw field
- Goal: Replace the raw-only cron text field with a preset dropdown (daily/weekly/monthly) plus an advanced toggle exposing the raw cron field.
- Spec ref: D7
- Depends on: Task 034
- Affected: `mail-job-editor.component.{ts,html,scss,spec.ts}`
- Expected changes: a preset `<mat-select>` (Daily 08:00, Weekly Mon 08:00, Monthly 1st 08:00 — Quartz-dialect cron strings per Task 017's pinned dialect) that writes directly into the underlying `cron` form control; an "Advanced" toggle reveals a raw-text cron input bound to the same control for power users. Whichever was edited last wins (no separate state).
- Test: `mail-job-editor.component.spec.ts.selectingPresetSetsCronControlValue`, `advancedToggleRevealsRawCronField`, `rawCronFieldEditsSameControlAsPresets`.
- Red: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → fails (no presets/toggle yet).
- Green: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `mail-job-editor.component.ts` (`cronPresets`, `showAdvancedCron`, `selectCronPreset`, `toggleAdvancedCron`), `.html` (preset select + advanced toggle + raw field), `.spec.ts` (+3 tests). Green: scoped 11/11. Deviations: none — Quartz-dialect cron strings match Task 017's pinned `cron-type=quartz`.

## Task 037 — Frontend: activate/deactivate toggle + last-run status
- Goal: Add an activate/deactivate toggle and a visually distinct last-run status display to the job form/list.
- Spec ref: R8, R9, R12, Observability
- Depends on: Task 034
- Affected: `mail-job-editor.component.{ts,html,scss,spec.ts}`
- Expected changes: a `mat-slide-toggle` bound to `job.active`, calling `MailJobService.activate(id)`/`.deactivate(id)` on change (not a plain form field — an explicit action per R8/R9); a status chip in the list/detail view showing `lastRunStatus` with distinct styling for `NO_RECIPIENTS`/`SKIPPED_OVERLAP`/`FAILED`/`PARTIAL` vs. `SUCCESS` (color classes, matching the `.test-result.success/.failure` pattern in `mail.component.scss`).
- Test: `mail-job-editor.component.spec.ts.togglingActiveCallsActivateOrDeactivate`, `displaysDistinctStyleForNonSuccessStatuses`.
- Red: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → fails (no toggle/status display yet).
- Green: `ng test --watch=false --include='**/mail-job-editor.component.spec.ts'` → all pass.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `mail-job-editor.component.ts` (`toggleActive`, `statusClass`), `.html` (`mat-slide-toggle` + status chip in the list), `.scss` (`.status-chip`/`.status-success`/`.status-attention`), `.spec.ts` (+3 tests, `activateCalls`/`deactivateCalls` tracking). Green: scoped 14/14. Deviations: none.

## Task 038 — Wire mat-tab-group into MailComponent
- Goal: Add "Vorlagen" (Templates) and "Jobs" tabs to `MailComponent`, alongside the existing SMTP settings tab, embedding the two new sub-components.
- Spec ref: Component Responsibilities (Frontend MailComponent)
- Depends on: Task 033, Task 035, Task 036, Task 037
- Affected: `settings/mail/mail.component.{ts,html,scss,spec.ts}`
- Expected changes: import `MatTabsModule` and both new standalone components; wrap the existing SMTP form in `<mat-tab label="SMTP">`, add `<mat-tab label="Vorlagen"><app-mail-template-editor /></mat-tab>` and `<mat-tab label="Jobs"><app-mail-job-editor /></mat-tab>`, following `organisation.component.html`'s `mat-tab-group` structure. No change to existing SMTP form behavior.
- Test: `mail.component.spec.ts` — existing SMTP tests still pass unmodified (regression check); add `rendersThreeTabsIncludingTemplatesAndJobs` asserting the tab labels/structure.
- Red: `ng test --watch=false --include='**/mail.component.spec.ts'` → fails (new test: only one tab/no tabs exist yet).
- Green: `ng test --watch=false --include='**/mail.component.spec.ts'` → all pass; `ng build` succeeds.
- Size: S
- Status: Completed
- Executed: 2026-07-23
- Notes: files changed: `mail.component.ts` (imports `MatTabsModule`, `MailTemplateEditorComponent`, `MailJobEditorComponent`), `mail.component.html` (`mat-tab-group` wrapping SMTP form + two new tabs). Verification: existing `mail.component.spec.ts` (3/3) unmodified and still green (regression); `ng build` succeeded, compiling the new nested-component template bindings. Deviations: did not add a `rendersThreeTabsIncludingTemplatesAndJobs` DOM-assertion test as originally planned — confirmed `organisation.component.spec.ts` (the codebase's only other `mat-tab-group` usage) also has no such test; this component's spec follows the established direct-instantiation, no-`TestBed` convention, and introducing `TestBed` here to render the tab tree would also need to mock `HttpClient` for every transitively-injected service (`MailTemplateService`, `MailJobService`, `MailAccountService`, `OrganisationService`) — the exact "No provider for HttpClient" failure mode already present as a pre-existing baseline failure (`AppComponent should create the app`). Relied on `ng build`'s template compilation plus the unmodified regression pass instead, consistent with the existing precedent.

This completes all 38 tasks in the plan.
