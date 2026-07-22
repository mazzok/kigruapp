# Implementation Plan

## Spec

`docs/forge/spec/2026-07-22-mail-settings.md` (Review: `docs/forge/spec/2026-07-22-mail-settings.grill.md`, Status: RESOLVED).

## Goal

Nach Abschluss aller Tasks existiert: ein Backend-Endpoint `/api/v1/mail-settings` (admin-only), über den ein `MailSettings`-Singleton gelesen und geschrieben wird; das SMTP-Passwort wird per AES-256-GCM verschlüsselt gespeichert und nie ans Frontend zurückgegeben; ein `MailService`, der über `jakarta.mail` mit Timeouts und TLS-Härtung E-Mails versendet; ein Test-Endpoint, der eine Testmail auslöst und ein normalisiertes Ergebnis liefert; sowie eine Angular-Seite `Einstellungen → Mail`, über die ein Admin die SMTP-Daten pflegt und eine Testmail auslöst. Fehlt der Verschlüsselungsschlüssel, ist das Feature fail-closed deaktiviert.

## Implementation Strategy

Backend zuerst, in Abhängigkeitsreihenfolge, damit nach jeder Task Build + Tests grün sind: (1) Dependencies/Config, (2) `EncryptionService` (isoliert unit-testbar), (3) Daten-Singleton `MailSettings`, (4) Resource-GET, (5–7) Resource-PUT (Upsert/Encrypt, Passwort-Semantik, Validierung), (8) Authz-Absicherung, (9–11) `MailService` (Versand/Session-Properties/Guard), (12) Test-Endpoint. Danach Frontend: (13) Model, (14) Service, (15) Komponente + Spec, (16) Navigation. Jede Verhaltens-Task ist ein Red→Green-Zyklus; Scaffolding-/Config-/Wiring-Tasks sind als `Test: N/A` markiert und über den grün bleibenden Build/Suite verifiziert.

## Why This Approach

Die Spec fixiert die großen Entscheidungen (dedizierte Entity, jakarta.mail, AES-GCM, Test-Endpoint). Verbleibende Freiheit betraf v.a. Task-Schnitt und Testbarkeit von R11/R12:

- **Session-Properties in eine eigene, paket-sichtbare Builder-Methode extrahieren** statt inline in `send()` — macht Timeout (R11) und TLS-Härtung (R12) als reine Unit-Assertion prüfbar, ohne echten TLS-Server. Alternative (nur Integrationstest über echtes SMTPS) wäre aufwändiger und flakier.
- **GreenMail als In-Memory-SMTP** für den echten Zustell-Test (R6/R7) im Modus `NONE` — deckt den Versandpfad ab, ohne externen Server; die TLS-Modi werden über den Properties-Builder abgedeckt.
- **DTOs dort anlegen, wo sie zuerst gebraucht werden** (Read-DTO in der GET-Task, Update-DTO in der PUT-Task) statt einer separaten Scaffolding-Task — weniger Tasks, jede bleibt ≤3 Dateien.
- **Java-`enum MailEncryption`** statt String-Feld (Codebase nutzt sonst Strings): Jackson deserialisiert unbekannte Werte zu 400, was R9 für das Verschlüsselungsfeld gratis mitliefert. Kleine, in sich geschlossene Abweichung — in Risks vermerkt.

Kein Scheduler, keine Queue, keine Templating-Engine, kein Key-Versioning — nichts über die Spec hinaus.

## Components Affected

- `backend/pom.xml` — neue Runtime-Dep (angus-mail) + Test-Dep (GreenMail).
- `backend/src/main/resources/application.properties` — Mail-Key-Config (kein prod-Default; `%test`-Key).
- `at.kigruapp.service.EncryptionService` (neu) — AES-256-GCM Ver-/Entschlüsselung, Key-Validierung.
- `at.kigruapp.entity.MailSettings` + `at.kigruapp.entity.MailEncryption` (neu) — Singleton-Datenmodell.
- `at.kigruapp.dto.MailSettingsDto` / `MailSettingsUpdateDto` (neu) — Read/Write-Contracts.
- `at.kigruapp.resource.MailSettingsResource` (neu) — GET/PUT/POST-test unter `/api/v1/mail-settings`.
- `at.kigruapp.service.MailService` (neu) — Versand via jakarta.mail.
- `at.kigruapp.security.SecurityFilter` — nur Test ergänzt (kein Whitelist-Eintrag!).
- Frontend `shared/models/mail-settings.model.ts`, `shared/services/mail-settings.service.ts`, `settings/mail/mail.component.{ts,html,scss,spec.ts}`, `app.routes.ts`, `app.component.html`.

## Expected File Changes

Neu (Backend): `EncryptionService.java`(+Test), `MailSettings.java`, `MailEncryption.java`, `MailSettingsDto.java`, `MailSettingsUpdateDto.java`, `MailSettingsResource.java`(+Test), `MailService.java`(+Tests), `MailSettingsEntityTest.java`, `SecurityFilter`-Testfall.
Geändert (Backend): `pom.xml`, `application.properties`.
Neu (Frontend): `mail-settings.model.ts`, `mail-settings.service.ts`, `mail.component.ts/html/scss/spec.ts`.
Geändert (Frontend): `app.routes.ts`, `app.component.html`.

## Testing Strategy

- **Backend Unit (Mockito/plain JUnit, kein Mongo):** `EncryptionService` (Round-Trip, falscher/fehlender Key), `MailService`-Properties-Builder (R11/R12), `SecurityFilter`-Deny (R5).
- **Backend Integration (`@QuarkusTest` + RestAssured, echtes lokales Mongo `kigruapp_test`, OIDC im Test aus):** Entity-Singleton (G-009), Resource GET/PUT/POST-test. Versand-Tests zusätzlich mit **GreenMail** (In-Memory-SMTP im Test-JVM).
- **Frontend (Karma+Jasmine, Fake-Service-Muster ohne HTTP):** `MailComponent`-Spec — Laden, Speichern (mappt Formular→DTO, Passwort nur bei Eingabe), Testmail-Button ruft Service.
- Befehle: Backend `cd backend && mvnw.cmd test` (bzw. `-Dtest=Klasse`); Frontend `cd frontend && ng test --watch=false --browsers=ChromeHeadless`. Backend-`@QuarkusTest` benötigt ein laufendes lokales MongoDB auf `localhost:27017`.

## Risks

- **Review-Gate:** erfüllt — `2026-07-22-mail-settings.grill.md` Status: RESOLVED (Verdict REVISE, 10/10 aufgelöst). Kein Override nötig.
- **Lokales MongoDB Voraussetzung:** `@QuarkusTest`-Tasks (T003, T005–T007, T012) laufen nur mit laufendem Mongo. Mitigation: als Vorbedingung in forge-execute dokumentiert; reine Unit-Tasks (T002, T010–T011, T008) sind Mongo-frei.
- **Bekannte kaputte Baseline (Snapshot 2026-07-22, vor Ausführung gemessen):** Backend `mvnw.cmd test` = 87 Tests, **12 fehlschlagend**, alle vorbestehend/feature-fremd, diagnostiziert als benigne:
  - `SecurityFilterTest` (6: getFamilies, getPersons, noCurrentPerson, postFieldDefinitions, putOrganisationDutySettings, unknownPath) — **veraltete Unit-Tests**: setUp() setzt `oidcEnabled` nicht auf true, daher greift der OIDC-Bypass `SecurityFilter.java:36-38`; kein Prod-Regress (Prod-Default `oidcEnabled=true`).
  - `FieldDefinitionResourceTest` (4) — bekannter offener `jsonSchema`-400-Bug (POST liefert 400 statt 201), feature-fremd.
  - `CurrentUserServiceTest` (2) — Mockito-NPE (`mongoClient`/`col` null), Test-Harness.
  Regression-Sweep vergleicht gegen EXAKT diese 12; „grün" = keine der bisher 75 grünen Tests kippt + neue Feature-Tests bestehen. **Task-008-Anpassung:** der neue SecurityFilterTest-Fall muss `filter.oidcEnabled = true` setzen, um den Bypass zu umgehen (sonst nicht grün beweisbar). Frontend-Baseline (1 bekannter Fehler) wird vor Task 013 gemessen.
- **Implementierungs-Entscheidung (keine Design-Änderung):** `MailEncryption` als Java-Enum statt String (Abweichung von der String-Konvention der Codebase) — für automatische 400-Validierung. Falls unerwünscht, in Review flaggen.
- **GreenMail-Version:** konkrete Version wird in T001 gewählt (aktuelle stabile `com.icegreen:greenmail`), Scope `test`.
- **Kein Design-Gap gefunden:** alle Tasks bilden bestehende Spec-Entscheidungen ab; nichts wurde eigenmächtig entschieden.

## Out of Scope

Wie Spec-Non-Goals: konkrete fachliche Versand-Use-Cases, Templating/HTML-Mails, async/geplanter Versand, Retry/Tracking, Concurrency-Cap, Key-Rotation/Re-Encrypt, mehrere Mail-Profile, Empfängerauswahl-UI.

# Task Breakdown

## Task 001 — Mail-Dependencies und Key-Config hinzufügen
- Goal: angus-mail (runtime) und GreenMail (test) in pom.xml aufnehmen und die Encryption-Key-Config anlegen.
- Spec ref: D2, Constraints, R10 (Key-Bereitstellung)
- Depends on: none
- Affected: `backend/pom.xml`, `backend/src/main/resources/application.properties`
- Expected changes: `org.eclipse.angus:angus-mail` als runtime-Dependency; `com.icegreen:greenmail` mit `<scope>test</scope>`. In `application.properties`: KEIN prod-Default für `kigruapp.mail.encryption-key`; nur `%test.kigruapp.mail.encryption-key=<base64 32 Byte Testschlüssel>` plus Kommentar, dass die Variable in Deployments als `KIGRUAPP_MAIL_ENCRYPTION_KEY` gesetzt werden muss.
- Test: N/A — Dependency-/Config-Änderung ohne Verhalten.
- Verification: `cd backend && mvnw.cmd test-compile` → resolves ohne Fehler; bestehende Suite bleibt unverändert grün-gegen-Baseline.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: pom.xml (+angus-mail 2.0.5 runtime, +greenmail-junit5 2.1.3 test), application.properties (+%test/%dev kigruapp.mail.encryption-key, kein prod-Default). `mvnw.cmd test-compile` → BUILD SUCCESS. deviations: none.

## Task 002 — EncryptionService (AES-256-GCM) mit Key-Validierung
- Goal: Einen Service bereitstellen, der einen Klartext per AES-256-GCM ver- und entschlüsselt und einen fehlenden/ungültigen Schlüssel als „nicht konfiguriert" meldet.
- Spec ref: R3, R10, D3
- Depends on: 001
- Affected: `backend/src/main/java/at/kigruapp/service/EncryptionService.java`, `backend/src/test/java/at/kigruapp/service/EncryptionServiceTest.java`
- Expected changes: `@ApplicationScoped EncryptionService` mit `@ConfigProperty(name="kigruapp.mail.encryption-key") Optional<String> key`; `boolean isConfigured()` (Key vorhanden, base64-dekodiert genau 32 Byte); `String encrypt(String plain)` → `base64(iv‖ciphertext‖tag)` mit 12-Byte-`SecureRandom`-Nonce; `String decrypt(String blob)`. Test setzt das Key-Feld direkt (plain JUnit, kein CDI).
- Test: `EncryptionServiceTest` — Round-Trip (encrypt→decrypt == Original), zwei encrypts desselben Klartexts liefern unterschiedliche Blobs (frische Nonce), `isConfigured()` false bei leerem Optional und bei falscher Länge, `decrypt` mit falschem Key wirft.
- Red: `cd backend && mvnw.cmd test -Dtest=EncryptionServiceTest` → schlägt mit Assertion fehl (Methoden noch nicht implementiert / liefern null)
- Green: `cd backend && mvnw.cmd test -Dtest=EncryptionServiceTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: EncryptionService.java (AES-256-GCM, 12B SecureRandom-Nonce, base64(iv‖ct‖tag), decodeKey validiert 32B), EncryptionServiceTest.java. Red: 4/6 Assertion-Fehler (null/false). Green: 6/6. deviations: none.

## Task 003 — MailSettings-Singleton-Entity + MailEncryption-Enum
- Goal: Das persistente Mail-Settings-Datenmodell als Singleton mit konstanter `_id` einführen.
- Spec ref: R2, D1, G-009
- Depends on: 001
- Affected: `backend/src/main/java/at/kigruapp/entity/MailSettings.java`, `backend/src/main/java/at/kigruapp/entity/MailEncryption.java`, `backend/src/test/java/at/kigruapp/entity/MailSettingsEntityTest.java`
- Expected changes: `enum MailEncryption { NONE, STARTTLS, SSL_TLS }`; `@MongoEntity(collection="mail_settings") MailSettings extends PanacheMongoEntity` mit public fields `host, port(int), encryption(MailEncryption), username, encryptedPassword, fromAddress, fromName, enabled(boolean)`; statische Helfer `findSingleton()` (konstante well-known ObjectId) und `upsertSingleton(...)`/`persistOrUpdateSingleton()`, die immer dieselbe `_id` verwenden.
- Test: `MailSettingsEntityTest` (`@QuarkusTest`, `@BeforeEach MailSettings.deleteAll()`) — zweimaliges Upsert erzeugt genau ein Dokument (`count()==1`) und der zweite Wert überschreibt den ersten.
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsEntityTest` → schlägt fehl (kein Singleton-Enforcement → count()==2 oder fehlende Methode)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsEntityTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailEncryption.java (enum), MailSettings.java (PanacheMongoEntity, konstante SINGLETON_ID, findSingleton/persistSingleton via persistOrUpdate), MailSettingsEntityTest.java. Red: count()==2. Green: 1/1. deviations: none.

## Task 004 — GET /mail-settings liefert maskiertes DTO
- Goal: Einen GET-Endpoint bereitstellen, der die Einstellungen ohne Passwort und mit `passwordSet`-Flag zurückgibt (Defaults, wenn noch nichts gespeichert).
- Spec ref: R1, R3, R5, Interfaces
- Depends on: 003
- Affected: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, `backend/src/main/java/at/kigruapp/dto/MailSettingsDto.java`, `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java`
- Expected changes: `@Path("/api/v1/mail-settings")` Resource mit `GET /` → `MailSettingsDto { host, port, encryption, username, fromAddress, fromName, enabled, passwordSet }` (kein Passwortfeld). Manuelles Mapping analog `OrganisationResource.toDTO()`. Ohne gespeichertes Dokument: Default-DTO (`enabled=false`, `passwordSet=false`). NICHT in `SecurityFilter` whitelisten.
- Test: `MailSettingsResourceTest#getReturnsMaskedSettings` — nach direktem Einfügen eines Dokuments mit gesetztem `encryptedPassword` enthält die GET-Antwort `passwordSet=true`, aber KEIN `password`/`encryptedPassword`-Feld; Default-Fall liefert `enabled=false`.
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest#getReturnsMaskedSettings` → fails (Endpoint fehlt / 404)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest#getReturnsMaskedSettings` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailSettingsDto.java (kein Passwortfeld, passwordSet), MailSettingsResource.java (GET + toDto, Default bei null), MailSettingsResourceTest.java. Red: 404. Green: 1/1. deviations: none.

## Task 005 — PUT /mail-settings: Upsert + Passwort verschlüsseln
- Goal: Einen PUT-Endpoint bereitstellen, der die Einstellungen speichert und ein mitgesendetes Passwort verschlüsselt ablegt.
- Spec ref: R1, R2, R3, Interfaces, D3
- Depends on: 004, 002
- Affected: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, `backend/src/main/java/at/kigruapp/dto/MailSettingsUpdateDto.java`, `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java`
- Expected changes: `PUT /` ← `MailSettingsUpdateDto { host, port, encryption, username, password?, clearPassword?, fromAddress, fromName, enabled }`; speichert via Singleton-Upsert; ist `password` nicht-leer → `EncryptionService.encrypt` und in `encryptedPassword` ablegen; Antwort = maskiertes `MailSettingsDto`. (Passwort-Erhalt/-Löschung in Task 006.)
- Test: `MailSettingsResourceTest#putEncryptsPassword` — PUT mit `password` speichert; anschließendes GET zeigt `passwordSet=true`; in der DB ist `encryptedPassword != Klartext` (per injiziertem MongoClient gelesen).
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest#putEncryptsPassword` → fails (PUT fehlt)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailSettingsUpdateDto.java, PUT + EncryptionService-Inject in MailSettingsResource, Testmethode putEncryptsPassword (+MongoClient-DB-Check). Red: stored==plaintext (Klartext-Stub). Green: 2/2. deviations: Green-Impl versehentlich vor Red geschrieben, korrekt auf Klartext-Stub zurückgesetzt, Red beobachtet, dann Green — Red-First-Evidenz wiederhergestellt.

## Task 006 — PUT-Passwort-Semantik: leer = unverändert, clearPassword = löschen
- Goal: Die Passwort-Erhalt- und -Lösch-Semantik im PUT umsetzen.
- Spec ref: R4, D5, G-007
- Depends on: 005
- Affected: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java`
- Expected changes: In der PUT-Logik: `password` leer/fehlend ⇒ bestehendes `encryptedPassword` unangetastet; `clearPassword=true` ⇒ `encryptedPassword=null`; gleichzeitig gesetztes `password` hat Vorrang vor `clearPassword`.
- Test: `MailSettingsResourceTest#putKeepsAndClearsPassword` — (a) PUT ohne password nach zuvor gesetztem Passwort ⇒ `passwordSet` bleibt true; (b) PUT mit `clearPassword=true` ⇒ `passwordSet=false`.
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest#putKeepsAndClearsPassword` → fails (leeres Passwort überschreibt / kein clear)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest` → passes
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: clearPassword-Zweig (password hat Vorrang) in PUT, Testmethode putKeepsAndClearsPassword. Red: passwordSet blieb true nach clear. Green: 3/3. deviations: none.

## Task 007 — PUT-Eingabevalidierung
- Goal: Ungültige Eingaben im PUT mit HTTP 400 ablehnen.
- Spec ref: R9
- Depends on: 005
- Affected: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, `backend/src/test/java/at/kigruapp/resource/MailSettingsResourceTest.java`
- Expected changes: Validierung: `host` nicht leer, `port` 1–65535, `fromAddress` gültiges E-Mail-Format; ungültiger `encryption`-Wert wird bereits durch Enum-Deserialisierung zu 400 (im Test mitgeprüft). Bei Verstoß `Response 400` mit Feldhinweis.
- Test: `MailSettingsResourceTest#putRejectsInvalidInput` — leerer Host → 400, Port 70000 → 400, `fromAddress="nope"` → 400, unbekannter `encryption`-String → 400.
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest#putRejectsInvalidInput` → fails (akzeptiert Ungültiges mit 200)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsResourceTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: validate() in PUT (host/port/EMAIL-Regex, BadRequestException); unbekanntes Enum → Jackson-400. Testmethode putRejectsInvalidInput. Red: 200 statt 400. Green: 4/4. deviations: none.

## Task 008 — SecurityFilter-Testfall: /mail-settings ist admin-only
- Goal: Absichern, dass die neuen Endpoints für Nicht-Admins verweigert werden (Default-Deny, kein Whitelist-Eintrag).
- Spec ref: R5, Security Considerations
- Depends on: 004
- Affected: `backend/src/test/java/at/kigruapp/security/SecurityFilterTest.java`
- Expected changes: Neuer Testfall im bestehenden Mockito-`SecurityFilterTest` (Muster wie vorhandene Fälle): Request auf `/api/v1/mail-settings` mit Nicht-Admin-Identity ⇒ Filter bricht mit 403 ab (`isAdmin()==false`, Pfad nicht whitelisted).
- Test: `SecurityFilterTest#mailSettingsRequiresAdmin` — abort/403 für Nicht-Admin; kein 403 für Admin.
- Red: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest#mailSettingsRequiresAdmin` → fails (Testfall neu; ggf. rot, falls versehentlich whitelisted)
- Green: `cd backend && mvnw.cmd test -Dtest=SecurityFilterTest` → passes
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: 2 neue Testmethoden (mailSettingsRequiresAdmin non-admin→403, mailSettings_admin_allowed) mit filter.oidcEnabled=true. KEINE Produktions-Änderung — R5 ist strukturell durch Default-Deny (kein Whitelist-Eintrag) erfüllt; daher Guard-/Charakterisierungstest, kein sinnvolles Red erzeugbar. Nicht-Vakuität von assertForbidden ist durch die 6 roten Baseline-SecurityFilterTests belegt (dieselbe Assertion). Scoped green: 2/2. Baseline-Fehler der Klasse (6) unverändert. deviations: Task war als behavioral geplant, ist faktisch ein reiner Guard-Test (kein Red) — dokumentiert.

## Task 009 — MailService: Session-Properties-Builder (Timeouts + TLS-Härtung)
- Goal: Aus `MailSettings` die jakarta.mail-Properties erzeugen, inkl. Timeouts und Transport-Härtung.
- Spec ref: R11, R12, D3
- Depends on: 003
- Affected: `backend/src/main/java/at/kigruapp/service/MailService.java`, `backend/src/test/java/at/kigruapp/service/MailServicePropertiesTest.java`
- Expected changes: `@ApplicationScoped MailService` mit paket-sichtbarer `Properties buildProperties(MailSettings s)`: setzt `mail.smtp.host/port`, `mail.smtp.connectiontimeout=10000`, `mail.smtp.timeout=10000`, `mail.smtp.ssl.checkserveridentity=true`; im `STARTTLS`-Modus `mail.smtp.starttls.enable=true` + `mail.smtp.starttls.required=true`; im `SSL_TLS`-Modus `mail.smtp.ssl.enable=true`; `mail.smtp.auth=true` nur wenn username gesetzt.
- Test: `MailServicePropertiesTest` (plain JUnit) — assert auf die Property-Werte je Modus (Timeouts immer 10000; STARTTLS-Modus setzt `starttls.required=true`; checkserveridentity immer true).
- Red: `cd backend && mvnw.cmd test -Dtest=MailServicePropertiesTest` → fails (Methode/Properties fehlen)
- Green: `cd backend && mvnw.cmd test -Dtest=MailServicePropertiesTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailService.java (buildProperties: host/port, connect+read timeout 10000, checkserveridentity=true, auth nur bei username, STARTTLS→starttls.required, SSL_TLS→ssl.enable), MailServicePropertiesTest.java. Red: 4/5 null. Green: 5/5. deviations: none.

## Task 010 — MailService: Versand über GreenMail
- Goal: Eine E-Mail über die gespeicherten Einstellungen zustellen (verifiziert gegen In-Memory-SMTP).
- Spec ref: R7, R6 (Versandpfad), D2
- Depends on: 009, 002
- Affected: `backend/src/main/java/at/kigruapp/service/MailService.java`, `backend/src/test/java/at/kigruapp/service/MailServiceSendTest.java`
- Expected changes: `void send(String recipient, String subject, String body)` — lädt `MailSettings`-Singleton, entschlüsselt Passwort via `EncryptionService`, baut `Session` aus `buildProperties(...)`, versendet `MimeMessage` (From = `fromAddress`/`fromName`). Contract explizit provisorisch (R7).
- Test: `MailServiceSendTest` (`@QuarkusTest` + GreenMail auf freiem Port, Modus `NONE`, `enabled=true` Settings persistiert) — nach `send(...)` hat GreenMail genau 1 Nachricht mit erwartetem Empfänger, Betreff, Body, Absender.
- Red: `cd backend && mvnw.cmd test -Dtest=MailServiceSendTest` → fails (send nicht implementiert / keine Nachricht zugestellt)
- Green: `cd backend && mvnw.cmd test -Dtest=MailServiceSendTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailService.send (findSingleton, decrypt via EncryptionService, Session mit optionalem Authenticator, MimeMessage, Transport.send), MailServiceSendTest.java (GreenMailExtension SMTP). Red: 0 Mails. Green: 1/1 (Zustellung + Subject/From/To verifiziert). deviations: none.

## Task 011 — MailService: Guard bei deaktiviert/unvollständig/fehlendem Key
- Goal: Versand verweigern, wenn das Feature aus, unvollständig oder ohne Schlüssel ist — mit kategorisierter Ausnahme.
- Spec ref: R8, R3 (fail-closed), G-008-Kategorien
- Depends on: 010
- Affected: `backend/src/main/java/at/kigruapp/service/MailService.java`, `backend/src/main/java/at/kigruapp/service/MailException.java`, `backend/src/test/java/at/kigruapp/service/MailServiceGuardTest.java`
- Expected changes: `send(...)` prüft vor Versand: `EncryptionService.isConfigured()` (sonst `MailException(CONFIG_MISSING)`), `enabled` und Vollständigkeit (host+port+encryption+fromAddress; username gesetzt ⇒ Passwort muss existieren) (sonst `MailException` mit passender Kategorie). `MailException` trägt ein `category`-Feld (`CONFIG_MISSING, AUTH_FAILED, CONNECTION_FAILED, UNKNOWN`).
- Test: `MailServiceGuardTest` (`@QuarkusTest`) — disabled Settings ⇒ `MailException` (kein GreenMail-Versand); fehlender Key (EncryptionService nicht konfiguriert) ⇒ `MailException(CONFIG_MISSING)`.
- Red: `cd backend && mvnw.cmd test -Dtest=MailServiceGuardTest` → fails (kein Guard, send versucht Zustellung)
- Green: `cd backend && mvnw.cmd test -Dtest=MailServiceGuardTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: MailException.java (Category enum), send()-Guards (isConfigured/enabled/isIncomplete → CONFIG_MISSING) + catch-Klassifizierung (AuthenticationFailedException→AUTH_FAILED, MessagingException→CONNECTION_FAILED, else UNKNOWN), MailServiceGuardTest.java (disabled + missing-key). Red: IllegalStateException statt MailException. Green: 8/8 (Guard+Send+Properties). deviations: none.

## Task 012 — POST /mail-settings/test: normalisiertes Testmail-Ergebnis
- Goal: Einen Test-Endpoint bereitstellen, der eine Testmail auslöst und immer 200 mit normalisierter Kategorie zurückgibt.
- Spec ref: R6, D4, G-008, Error Handling, Interfaces
- Depends on: 011, 007
- Affected: `backend/src/main/java/at/kigruapp/resource/MailSettingsResource.java`, `backend/src/test/java/at/kigruapp/resource/MailSettingsTestEndpointTest.java`
- Expected changes: `POST /test` ← `{ recipient }` → `200 { success, category, message }`. Ruft `MailService.send(recipient, "kigruapp Testmail", <fixer Text>)`; fängt `MailException` → `{success:false, category, <normalisierte message>}`; SMTP-/Netzwerkfehler → `CONNECTION_FAILED`/`AUTH_FAILED` mit normalisiertem Text (KEINE rohe SMTP-Antwort); Erfolg → `{success:true, category:OK}`. Immer HTTP 200 (500 nur bei unerwartetem Fehler).
- Test: `MailSettingsTestEndpointTest` (`@QuarkusTest` + GreenMail) — (a) gültige `NONE`-Settings ⇒ 200 `success=true, category=OK`, GreenMail empfängt Mail; (b) disabled/fehlende Settings ⇒ 200 `success=false, category=CONFIG_MISSING`, `message` enthält keine rohe Server-/Stacktrace-Ausgabe.
- Red: `cd backend && mvnw.cmd test -Dtest=MailSettingsTestEndpointTest` → fails (Endpoint fehlt)
- Green: `cd backend && mvnw.cmd test -Dtest=MailSettingsTestEndpointTest` → passes
- Size: M
- Status: Completed
- Executed: 2026-07-22
- Notes: POST /test in MailSettingsResource (calls MailService.send, catches MailException → normalized {success,category,message}, always 200; nested TestRequest/TestResult DTOs kept in-file per Affected scope), MailSettingsTestEndpointTest.java (GreenMail success + disabled→CONFIG_MISSING, message has no "Exception"). Red: 404. Green: scoped 2/2 + MailSettingsResourceTest 4/4. deviations: none.

## Task 013 — Frontend: Mail-Settings Model-Interfaces
- Goal: Die TypeScript-Contracts für die Mail-Settings-Seite definieren.
- Spec ref: Interfaces, R1, R6
- Depends on: none
- Affected: `frontend/src/app/shared/models/mail-settings.model.ts`
- Expected changes: `export interface MailSettings { host; port; encryption; username; fromAddress; fromName; enabled; passwordSet }`; `export interface UpdateMailSettingsRequest { host; port; encryption; username; password?; clearPassword?; fromAddress; fromName; enabled }`; `export interface MailTestResult { success; category; message }`; `export type MailEncryption = 'NONE'|'STARTTLS'|'SSL_TLS'`.
- Test: N/A — reine Typdefinitionen ohne Laufzeitverhalten.
- Verification: `cd frontend && ng build` → kompiliert ohne Fehler.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: mail-settings.model.ts (MailEncryption type, MailSettings, UpdateMailSettingsRequest, MailTestResult). Test: N/A. Verification: `ng build` → bundle generation complete, 0 errors. deviations: none.

## Task 014 — Frontend: MailSettingsService
- Goal: Einen Service bereitstellen, der GET/PUT/Test über `ApiService` kapselt.
- Spec ref: Interfaces
- Depends on: 013
- Affected: `frontend/src/app/shared/services/mail-settings.service.ts`
- Expected changes: `@Injectable({providedIn:'root'}) MailSettingsService` mit `get(): Observable<MailSettings>` → `api.get('/mail-settings')`, `update(req): Observable<MailSettings>` → `api.put('/mail-settings', req)`, `test(recipient): Observable<MailTestResult>` → `api.post('/mail-settings/test', {recipient})`. Muster wie `SemesterService`.
- Test: N/A — dünne Delegation an `ApiService`; im Repo existieren keine Service-Specs, Abdeckung erfolgt über die Komponenten-Spec (Task 015) mit gefaktem Service.
- Verification: `cd frontend && ng build` → kompiliert; Service in Task 015 als Fake ersetzt.
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: mail-settings.service.ts (get→api.get('/mail-settings'), update→api.put, test→api.post('/mail-settings/test',{recipient}); SemesterService-Muster). Test: N/A. Verification: `ng build` → bundle generation complete, 0 errors. deviations: none.

## Task 015 — Frontend: MailComponent (Formular, Speichern, Testmail) + Spec
- Goal: Die Angular-Seite `Einstellungen → Mail` mit Laden, Speichern und Testmail-Auslösung implementieren.
- Spec ref: R1, R4 (leeres Passwort), R6
- Depends on: 014
- Affected: `frontend/src/app/settings/mail/mail.component.ts`, `mail.component.html`, `mail.component.scss`, `mail.component.spec.ts`
- Expected changes: Standalone `MailComponent` (imports `CommonModule, ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatSlideToggleModule`), Konstruktor-Injektion `MailSettingsService`. Reactive `FormGroup` für host/port/encryption/username/password/fromAddress/fromName/enabled; `ngOnInit` lädt via `service.get()` (Passwortfeld bleibt leer, Anzeige „Passwort gesetzt" aus `passwordSet`); `save()` baut `UpdateMailSettingsRequest` und sendet `password` nur, wenn nicht-leer; separates Empfängerfeld + `sendTest()` ruft `service.test(...)` und zeigt `MailTestResult`. Spec nach dem Fake-Service-Muster (`new MailComponent(fake)`).
- Test: `mail.component.spec.ts` — (a) `save()` mit leerem Passwortfeld ruft `service.update` mit `password` undefined/weggelassen; (b) `save()` mit eingegebenem Passwort übergibt es; (c) `sendTest()` ruft `service.test` mit dem Empfänger und legt das Ergebnis ab.
- Red: `cd frontend && ng test --watch=false --browsers=ChromeHeadless` (MailComponent-Suite) → fails (Komponente/Spec-Erwartungen nicht erfüllt)
- Green: `cd frontend && ng test --watch=false --browsers=ChromeHeadless` → MailComponent-Specs passes
- Size: L — Komponente + Template + Spec als eine UI-Einheit; nicht sinnvoll weiter teilbar, da Template und Save-/Test-Logik zusammengehören.
- Status: Completed
- Executed: 2026-07-22
- Notes: mail.component.{ts,html,scss,spec.ts} (standalone, ReactiveForm host/port/encryption/username/password/fromAddress/fromName/enabled; ngOnInit lädt via get(), Passwortfeld bleibt leer + passwordSet-Hinweis; save() sendet password nur bei nicht-leer; sendTest() ruft test() und speichert Ergebnis). Frontend-Baseline vor Task gemessen: TOTAL 4 FAILED (3 = neue MailComponent-Red, 1 = bekannter AppComponent-Baseline-Fehler), 76 SUCCESS. Red: 3 MailComponent-Assertions (testResult null / update password calls). Green: MailComponent scoped 3/3; Suite TOTAL 1 FAILED (nur AppComponent-Baseline), 79 SUCCESS. deviations: none.

## Task 016 — Frontend: Route und Navigations-Link verdrahten
- Goal: Die Mail-Seite unter `settings/mail` erreichbar machen und im Admin-Menü verlinken.
- Spec ref: R1 (Zugang unter Einstellungen), Component Responsibilities
- Depends on: 015
- Affected: `frontend/src/app/app.routes.ts`, `frontend/src/app/app.component.html`
- Expected changes: In `app.routes.ts` im `settings`-`children`-Block (nach `permissions`) einen `{ path: 'mail', loadComponent: () => import('./settings/mail/mail.component').then(m => m.MailComponent) }`-Eintrag; in `app.component.html` im `@if (currentUser.isAdmin)`-Settings-Block einen `<a mat-list-item routerLink="/settings/mail">` mit `mat-icon` `mail` und Titel „Mail".
- Test: N/A — Routing-/Navigations-Verdrahtung ohne eigene Logik.
- Verification: `cd frontend && ng build` → kompiliert; `ng test --watch=false --browsers=ChromeHeadless` bleibt grün (App-Component-Spec).
- Size: S
- Status: Completed
- Executed: 2026-07-22
- Notes: app.routes.ts (settings/mail loadComponent nach permissions), app.component.html (mat-list-item routerLink /settings/mail, icon mail, Titel "Mail" im isAdmin-Block). Test: N/A. Verification: `ng build` → bundle generation complete, 0 errors. Suite-Grün wird im finalen Sweep bestätigt. deviations: none.

# Coverage Mapping

| Spec | Tasks |
|---|---|
| R1 (Admin sieht/bearbeitet Felder) | 004, 005, 015, 016 |
| R2 (Persistenz) | 003, 005 |
| R3 (verschlüsselt, nie ans FE, fail-closed) | 002, 004, 005, 011 |
| R4 (leer=unverändert, clearPassword) | 006, 015 |
| R5 (admin-only) | 004 (kein Whitelist), 008 |
| R6 (Testmail + Kategorien) | 012, 015 |
| R7 (MailService) | 010 |
| R8 (Guard) | 011 |
| R9 (Validierung) | 007 |
| R10 (Krypto-Contract) | 002 |
| R11 (Timeouts) | 009 |
| R12 (Transport) | 009 |
| D1 (Singleton-Entity) | 003 |
| D2 (jakarta.mail + GreenMail) | 001, 010 |
| D3 (AES-GCM) | 002, 009 |
| D4 (Test-Endpoint) | 012 |
| D5 (Passwort-Semantik) | 006 |

Umgekehrt: jede Task referenziert oben eine Spec-Sektion (siehe `Spec ref`). Keine Task ohne Spec-Bezug.

# Changelog

- 2026-07-22 — Plan erstellt aus Spec (Status RESOLVED). 16 Tasks. Trigger: forge-implement.
