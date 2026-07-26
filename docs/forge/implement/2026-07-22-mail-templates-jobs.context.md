# Codebase Brief — mail-templates-jobs

<!-- Stable facts only — anything the plan modifies is NOT here. Verify against current file content before relying on it. -->

## Build / test commands (this environment)

- **IMPORTANT**: the git-bash `Bash` tool is intercepted by a context-mode hook for build commands and fails (`mvnw`/`ng` here fail with a broken Maven Classworlds launcher error under that hook's sandboxed shell). Use the **PowerShell** tool for all `mvnw.cmd` / `npm` / `ng` commands instead — confirmed working.
- Backend, from `D:\GIT\kigruapp\backend`:
  - All tests: `.\mvnw.cmd -q -DskipITs test`
  - Scoped: `.\mvnw.cmd -q -DskipITs test "-Dtest=<ClassName>"` or `"-Dtest=<ClassName>#<methodName>"`
  - Compile only: `.\mvnw.cmd -q compile`
- Frontend, from `D:\GIT\kigruapp\frontend`:
  - All tests: `npx ng test --watch=false --browsers=ChromeHeadless`
  - Scoped: `npx ng test --watch=false --browsers=ChromeHeadless --include='<glob>'`
  - Build: `npx ng build`

## Baseline (recorded at run start, 2026-07-23)

- Backend: `Tests run: 110, Failures: 6, Errors: 3, Skipped: 75` — pre-existing, unrelated to this feature (per project memory). Known-failing classes include `SecurityFilterTest` (several `nonAdmin_returns403` cases — Mockito "zero interactions" failures), `MailSettingsEntityTest.upsertKeepsSingleAndOverwrites` (Quarkus startup failure), `CurrentUserServiceTest.isAdmin_*` (NPE, `col` null). None of these are touched by this plan's tasks.
- Frontend: `80 executed, 1 FAILED, 79 SUCCESS` — `AppComponent should create the app` (`NullInjectorError: No provider for HttpClient`), pre-existing, unrelated.
- The final regression sweep must not add failures beyond this baseline; it does not need to fix these pre-existing ones.

## Directory layout (affected areas)

- Backend source root: `backend/src/main/java/at/kigruapp/`
  - `entity/` — Panache entities (flat, public-field style)
  - `resource/` — JAX-RS resources (`@Path("/api/v1/...")`)
  - `service/` — `@ApplicationScoped` services
  - `security/` — `SecurityFilter`, `CurrentUserService`
  - `migration/` — `@Startup` beans (existing convention; new `scheduler/` package is a new sibling, not placed here, since it's not a one-time migration)
  - No existing `scheduler/` package — this plan creates it fresh for `MailJobScheduler` / `MailJobStartupRearmer`.
- Backend test root: `backend/src/test/java/at/kigruapp/{entity,resource,service,security,scheduler}/` — mirrors main package structure exactly.
- `backend/src/main/resources/application.properties` — single file, `%profile.` prefix convention (`%dev.`, `%test.`), no separate per-profile files.
- Frontend source root: `frontend/src/app/`
  - `shared/models/`, `shared/services/` — flat model/service pairs, one file each, no subfolders
  - `settings/mail/` — existing `mail.component.{ts,html,scss,spec.ts}`; new sub-components go in their own subfolders per the plan (`mail-template-editor/`, `mail-job-editor/`)
  - `core/services/api.service.ts` — the only HTTP client wrapper; `core/interceptors/auth.interceptor.ts` attaches the bearer token
  - `app.routes.ts` — lazy `loadComponent` routes; `settings` parent is `[authGuard, adminGuard]`-protected

## Stable contracts (verify before use — file:line may drift as this plan touches nearby code)

- `PanacheMongoEntity` gives `id: ObjectId`, `persist()`, `update()`, `delete()`, `listAll()`, `listAll(Sort)`, `list(String, Object)`, `findById(ObjectId)`, `deleteAll()`.
- `FieldDefinition.findActive()` → `List<FieldDefinition>` (active = `outdatedAt == null`); fields: `fieldName`, `label (Map<String,String>)`, `jsonSchema`, `keycloakMapping`, `outdatedAt`.
- `Person` fields: `familyId (ObjectId)`, `basicProperties/roles/schedules/duties/finance/customProperties (List<FieldRef>)`; `Person.findByFamilyId(ObjectId)`.
- `Family`: `PanacheMongoEntity` with `name`, `address`, `createdAt`.
- `Semester`: `PanacheMongoEntity` with `start`, `end`, `createdAt`; newest-first via `Sort.descending("createdAt")` is the existing "default semester" convention (see `PersonResource.resolveSemesterId`).
- `MailSettings.findSingleton()` / `SINGLETON_ID` (fixed `ObjectId`), `MailSettings.persistSingleton()`.
- `MailService.send(String recipient, String subject, String body)` — existing plaintext method, untouched by this plan. `MailException` has `category` (`CONFIG_MISSING`/`AUTH_FAILED`/`CONNECTION_FAILED`/`UNKNOWN`) and a message.
- `EncryptionService.isConfigured()/encrypt(String)/decrypt(String)`.
- `SecurityFilter` — default-deny; a new resource is admin-only simply by **not** adding it to the whitelist. No code change needed there for this plan's endpoints.
- `mongoClient: MongoClient` + `@ConfigProperty(name = "quarkus.mongodb.database") String databaseName` is the established way to reach raw collections (`field_instances`, `semester_assignments`) not modeled as Panache entities — inject both, `mongoClient.getDatabase(databaseName).getCollection("<name>")`.
- `semester_assignments` doc shape: `{personId, semesterId, section: "group"|"team"|"role", definitionId, fieldInstanceId, entryDate?, exitDate?}`.
- Backend test convention: `@QuarkusTest` + real MongoDB (`%test.quarkus.mongodb.database=kigruapp_test`), `@BeforeEach void cleanup() { Entity.deleteAll(); }`, RestAssured `given()/when()/then()` for REST, `%test.kigruapp.mail.encryption-key` is pre-set so `EncryptionService.isConfigured()` is true under the test profile. `GreenMail` (`com.icegreen:greenmail-junit5`) is an existing test dependency for real-SMTP-shaped tests.
- Frontend `ApiService` (`core/services/api.service.ts`): `get<T>(path)`, `post<T>(path, body)`, `put<T>(path, body)`, `patch<T>(path, body?)`, `delete(path)` — all prefixed with `/api/v1`.
- Frontend test convention: no `TestBed` — `new XComponent(fakeService1, fakeService2, ...)` with fake service classes implementing the real service's shape and returning `of(...)`.
- `OrganisationService.getByTag(tag)` → `GET /organisation/${tag}` → `OrganisationDTO { id, tag, definitions: FieldDefinition[], entries }`. Used as `getByTag('groups')` for the group picker (Task 035) — no new group endpoint needed.
