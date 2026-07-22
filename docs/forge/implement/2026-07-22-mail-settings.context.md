# Codebase Brief — 2026-07-22-mail-settings

Run-stable facts only (things this run does not change). Created on resume before Task 012.

## Build & test commands

- Backend build/compile: `cd backend && mvnw.cmd test-compile`
- Backend scoped test: `cd backend && mvnw.cmd test -Dtest=<Class>` (or `<Class>#<method>`)
- Backend full suite: `cd backend && mvnw.cmd test`
- Frontend build: `cd frontend && ng build`
- Frontend test: `cd frontend && ng test --watch=false --browsers=ChromeHeadless`
- `@QuarkusTest` backend tests require a local MongoDB on `localhost:27017` (test DB `kigruapp_test`).

## Baseline (frozen snapshot 2026-07-22, pre-run)

Backend `mvnw.cmd test` = 87 tests, **12 pre-existing failures**, all feature-unrelated & benign:
- `SecurityFilterTest` (6) — outdated unit tests; setUp() doesn't set `oidcEnabled=true` → OIDC bypass.
- `FieldDefinitionResourceTest` (4) — known jsonSchema 400-bug.
- `CurrentUserServiceTest` (2) — Mockito NPE in harness.
Regression sweep compares against exactly these 12. Frontend baseline (1 known failure) measured before Task 013.

## Directory layout (affected areas)

- Backend main: `backend/src/main/java/at/kigruapp/{resource,service,entity,dto}/`
- Backend test: `backend/src/test/java/at/kigruapp/{resource,service,entity,security}/`
- Frontend: `frontend/src/app/{shared/models,shared/services,settings}/`

## Stable contracts (not modified by remaining tasks)

- `EncryptionService` (`service/`): `boolean isConfigured()`, `String encrypt(String)`, `String decrypt(String)`.
- `MailSettings extends PanacheMongoEntity` (`entity/`): public fields `host, port(int), encryption(MailEncryption), username, encryptedPassword, fromAddress, fromName, enabled(boolean)`; statics `findSingleton()`, instance `persistSingleton()`, `deleteAll()`.
- `MailEncryption` enum: `NONE, STARTTLS, SSL_TLS`.
- `MailService.send(String recipient, String subject, String body)` — throws `MailException` (category `CONFIG_MISSING|AUTH_FAILED|CONNECTION_FAILED|UNKNOWN`) on any failure; guards fail-closed on unconfigured/disabled/incomplete.
- `MailException` (`service/`): public final `Category category`; ctors `(Category, String)` and `(Category, String, Throwable)`.
- Resource: `MailSettingsResource` `@Path("/api/v1/mail-settings")`, JSON produces/consumes; `GET`, `PUT`; static `toDto(MailSettings)`. NOT whitelisted in `SecurityFilter` (default-deny → admin-only).
- DTOs (`dto/`): `MailSettingsDto` (no password field, has `passwordSet`), `MailSettingsUpdateDto` (`host, port, encryption, username, password?, clearPassword?, fromAddress, fromName, enabled`).

## Test patterns

- `@QuarkusTest` + RestAssured `given().when().get/put(...)`; `@BeforeEach MailSettings.deleteAll()`.
- GreenMail: `@RegisterExtension static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)`; settings point host `127.0.0.1`, port `greenMail.getSmtp().getPort()`, encryption `NONE`.
- Raw encrypted-DB read via injected `MongoClient` + `quarkus.mongodb.database` config property, collection `mail_settings`.
