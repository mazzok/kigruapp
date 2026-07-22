# Feature Design Specification — Mail-Einstellungen & E-Mail-Versand (Infrastruktur)

Review: required
Feature slug: mail-settings
Date: 2026-07-22

## Goal

Administratoren sollen die SMTP-Zugangsdaten der Anwendung über einen neuen Bereich unter *Einstellungen* selbst konfigurieren können, und die Anwendung soll auf Basis dieser Einstellungen E-Mails versenden können. Diese Spec liefert die **Infrastruktur**: die Admin-Konfigurationsseite, die persistente Speicherung der Einstellungen (inkl. sicher gespeichertem Passwort) und einen serverseitigen Versand-Service, dessen Funktion über eine Testmail verifizierbar ist. Konkrete fachliche Versand-Anwendungsfälle (welche Mails, an wen, ausgelöst wodurch) sind nicht Teil dieser Spec und folgen später.

## Requirements

- **R1** Ein Admin kann unter `Einstellungen → Mail` die SMTP-Einstellungen ansehen und bearbeiten: Host, Port, Verschlüsselung (STARTTLS / SSL/TLS / keine), Benutzername, Passwort, Absender-Adresse (From), Absender-Anzeigename, sowie ein „aktiviert"-Schalter.
- **R2** Die Einstellungen werden serverseitig persistent gespeichert und überleben Neustarts.
- **R3** Das SMTP-Passwort wird verschlüsselt at-rest gespeichert und niemals im Klartext (auch nicht verschlüsselt) an das Frontend zurückgegeben. Das Frontend erhält stattdessen nur die Information, ob ein Passwort gesetzt ist. Ist kein gültiger Verschlüsselungsschlüssel konfiguriert, wird das Mail-Feature hart deaktiviert (weder Passwort-Speichern noch Versand) mit klarer Fehlermeldung — es gibt keinen in der Anwendung mitgelieferten Default-Schlüssel (fail-closed).
- **R4** Beim Speichern ohne Eingabe eines neuen Passworts bleibt das bisher gespeicherte Passwort unverändert erhalten (leeres Passwortfeld = unverändert). Zum aktiven Entfernen eines gespeicherten Passworts (z.B. Wechsel auf einen No-Auth-Relay) gibt es eine explizite Aktion (`clearPassword`-Flag im PUT); nur diese löscht das Passwort.
- **R5** Nur Administratoren können die Mail-Einstellungen lesen und schreiben.
- **R6** Ein Admin kann über einen Button eine Testmail an eine eingegebene Empfängeradresse senden; das Ergebnis wird in der UI angezeigt. Das Ergebnis ist eine von wenigen normalisierten Kategorien (Erfolg / Konfiguration fehlt / Authentifizierung fehlgeschlagen / Verbindung fehlgeschlagen / unbekannter Fehler) — keine rohen SMTP-Serverantworten.
- **R7** Es existiert ein serverseitiger `MailService`, der eine E-Mail (Empfänger, Betreff, Textinhalt) unter Verwendung der gespeicherten, entschlüsselten SMTP-Einstellungen versendet. Der konkrete erste Consumer ist der Testmail-Endpoint (R6), der diese Signatur rechtfertigt. Der Contract ist **provisorisch/intern** — keine Stabilitätsgarantie; er wird beim ersten fachlichen Versand-Feature überprüft und ggf. angepasst.
- **R8** Ist der „aktiviert"-Schalter aus oder sind die Einstellungen unvollständig, wird kein Versand versucht; ein Aufruf des `MailService` schlägt mit einer klaren, geloggten Fehlermeldung fehl (kein stiller Fehlschlag). **Vollständig** bedeutet: host, port, encryption und fromAddress gesetzt. username ist optional; ist username gesetzt, aber nie ein Passwort gespeichert worden, gilt die Konfiguration als unvollständig.
- **R9** Eingaben werden validiert: Host nicht leer, Port im Bereich 1–65535, From eine gültige E-Mail-Adresse, Verschlüsselung einer der erlaubten Werte.
- **R10** Krypto-Contract: Der Verschlüsselungsschlüssel wird als base64-kodierte 32 Byte (AES-256) bereitgestellt und beim Start validiert (falsche Länge/fehlend → Mail-Feature deaktiviert, siehe R3-Erweiterung). Pro Verschlüsselung wird eine frische 12-Byte-Nonce via `SecureRandom` erzeugt; gespeichert wird `base64(iv‖ciphertext‖tag)` (AES-GCM).
- **R11** Der SMTP-Versand setzt harte Timeouts: connect-Timeout und read-Timeout je 10 Sekunden auf der `jakarta.mail`-Session. (Kein Concurrency-Cap in diesem Scope — Single-Admin-Nutzung, siehe Performance.)
- **R12** Transport-Sicherheit: Die Session erzwingt `mail.smtp.ssl.checkserveridentity=true`; im STARTTLS-Modus zusätzlich `mail.smtp.starttls.enable=true` und `mail.smtp.starttls.required=true` (kein Klartext-Fallback). Entschlüsselte Credentials dürfen nur über eine verifizierte TLS-Verbindung gehen.

## Success Criteria

- Ein Admin kann in der laufenden Anwendung SMTP-Daten eintragen, speichern, die Seite neu laden und sieht die (nicht-geheimen) Werte wieder — Passwortfeld leer, aber Anzeige „Passwort gesetzt".
- Ein Klick auf „Testmail senden" mit gültiger SMTP-Konfiguration stellt eine echte E-Mail im Zielpostfach zu; mit falschen Daten erscheint eine normalisierte Fehlerkategorie statt eines Absturzes.
- Automatisierter Test: `MailService.send` (R7) und der Testmail-Fluss (R6) werden gegen einen In-Memory-SMTP-Server (GreenMail) verifiziert — Zustellung, Absender/Empfänger, Betreff/Body prüfbar ohne echten SMTP.
- In der MongoDB ist das Passwort-Feld nicht als Klartext lesbar.
- Ohne konfigurierten `KIGRUAPP_MAIL_ENCRYPTION_KEY` verweigert die Anwendung das Speichern eines Passworts und den Versand (fail-closed), nachweisbar per Test.
- Ein nicht-Admin erhält bei Zugriff auf die Mail-Einstellungs-Endpoints eine Ablehnung (403/401).

## Existing Architecture

Verifiziert gegen den Code (siehe Explorer-Logs `docs/forge/log/2026-07-22-explorer-{1,2,3}.md`):

- **Backend:** Quarkus 3.36.1, Java 17, MongoDB via `quarkus-mongodb-panache`, Keycloak OIDC, Jackson REST (`backend/pom.xml:10,32-77`). JAX-RS-Resources werden automatisch entdeckt; Basis-Pfad `/api/v1`; Resource-Klassen unter `backend/src/main/java/at/kigruapp/resource/`.
- **Globaler Config-Präzedenzfall:** `entity/Organisation.java` (`@MongoEntity(collection="organisation")`, tag-basiert), gepflegt über `resource/OrganisationResource.java`. Generisch (Map-`entries`) — würde einen geheimen Wert über seinen generischen GET offenlegen.
- **Referenz-CRUD-Pattern:** `resource/FieldDefinitionResource.java` + Entity `entity/FieldDefinition.java` (extends `PanacheMongoEntity`) + `service/JsonSchemaValidatorService.java`.
- **Autorisierung:** `security/SecurityFilter.java` ist ein `ContainerRequestFilter` mit **Default-Deny** (Zeile 96) — jeder nicht explizit ge-whitelistete Pfad ist admin-only. Admin-Prüfung `security/CurrentUserService.isAdmin()` (`:77-97`).
- **Secrets heute:** ausschließlich `@ConfigProperty` aus `application.properties`/Umgebungsvariablen (Muster: `security/KeycloakUserService.java:15-25`). Kein `.env`, keine DB-gespeicherten Secrets. Ein DB-gespeichertes SMTP-Passwort ist ein **neues Muster**.
- **E-Mail heute:** Kein Versand-Code, keine deklarierte Mail-Dependency. `angus-mail`/`jakarta.mail-api` liegen nur transitiv (via keycloak-admin-client) unter `backend/target/…`, nicht deklariert, ungenutzt. Kein Scheduler/Queue.
- **Empfänger-Daten:** Personen-E-Mails liegen als dynamische `FieldInstance` (Feld-Key `email`, `migration/FieldDefinitionSeedMigration.java:70-73`), heute nur an Keycloak weitergereicht (`resource/PersonResource.java:215-232` → `security/KeycloakUserService.createUser()`).
- **Frontend:** Angular 18.2 standalone + Material 18.2. Bereits vorhandener `settings/`-Bereich (adminGuard) mit `organisation`, `custom-fields`, `permissions` (`frontend/src/app/app.routes.ts:65-91`), Nav-Links in `app.component.html:37-48`. Zentraler API-Wrapper `core/services/api.service.ts` (`baseUrl='/api/v1'`), Auth via `core/interceptors/auth.interceptor.ts`. Reactive Forms durchgängig; Referenz-Save z.B. `settings/organisation/organisation.component.ts:140-171`.

## Constraints

- Quarkus/Java 17 Backend, MongoDB Panache; keine neue Persistenz-Technologie.
- Angular 18.2 standalone + Material im Frontend; neuer API-Zugriff über den bestehenden `ApiService`.
- Admin-Autorisierung folgt dem bestehenden Default-Deny-Muster; kein neuer Auth-Mechanismus.
- Verschlüsselungsschlüssel wird als Konfiguration (`@ConfigProperty`/Env) bereitgestellt, nicht in der DB — konsistent mit dem bestehenden Secret-Muster.
- Neue Laufzeit-Abhängigkeit: die Mail-Bibliothek (`jakarta.mail`/angus-mail, siehe D2). Neue Test-Only-Abhängigkeit: GreenMail (In-Memory-SMTP für R6/R7-Tests).

## Design Decisions

**D1: Speicherung als dedizierte Singleton-Entity `MailSettings`, nicht als `Organisation`-Tag.**
- Rationale: Die Einstellungen sind strukturiert und sicherheitskritisch (verschlüsseltes Passwort). Eine dedizierte Entity erlaubt ein DTO, das das Passwort gezielt weglässt (R3). Der generische `OrganisationResource`-GET würde den (verschlüsselten) Passwort-Blob über die bestehende Map-`entries` mit ausliefern — unerwünscht.
- Alternatives: siehe „Alternatives Considered".
- Status: assumption (durch Sicherheitsanforderung R3 begründet).

**D2: Direkter `jakarta.mail`-Versand (angus-mail), explizit als Dependency deklariert — nicht `quarkus-mailer`.**
- Rationale: Die SMTP-Konfiguration kommt zur **Laufzeit aus der DB**. `quarkus-mailer` bindet seine Konfiguration an Build-/Startup-Zeit aus `application.properties` und ist für laufzeit-dynamische Zugangsdaten unpassend. Mit `jakarta.mail` wird pro Versand eine `Session` aus den DB-Einstellungen aufgebaut — exakt passend.
- Test-Seam (Antwort auf G-005): Da `quarkus-mailer` samt `MockMailbox` wegfällt, wird der `MailService` so strukturiert, dass der Verbindungs-/Session-Aufbau isolierbar ist, und **GreenMail** als Test-Only-Dependency für die automatisierte Verifikation von R6/R7 (echte SMTP-Zustellung gegen einen In-Memory-Server) aufgenommen.
- Alternatives: siehe „Alternatives Considered".
- Status: assumption — flag if wrong.

**D3: Passwort-Verschlüsselung at-rest per AES-256-GCM mit Schlüssel aus Konfiguration.**
- Rationale: Nutzerentscheidung „verschlüsselt in DB". AES-GCM (authentifiziert) mit einem `@ConfigProperty`-bereitgestellten Schlüssel (Env) hält den Schlüssel außerhalb der DB — konsistent mit dem bestehenden Secret-Muster.
- Krypto-Contract (R10): Schlüssel = base64-kodierte 32 Byte, beim Start validiert; **kein Default-Schlüssel** — fehlt/ungültig ⇒ Feature deaktiviert (fail-closed, R3). Nonce = 12 Byte `SecureRandom` pro Verschlüsselung. Gespeichert wird `base64(iv‖ciphertext‖tag)`. Entschlüsselung nur serverseitig im `MailService`.
- Key-Rotation ist **nicht unterstützt** (kein Key-Versioning im Blob — bewusst, um spekulative Komplexität zu vermeiden): nach Key-Wechsel/-Verlust scheitert die Entschlüsselung fail-closed, der Admin muss das Passwort neu eintragen.
- Transport (R12): `checkserveridentity=true` immer; STARTTLS-Modus erzwingt `starttls.required=true`.
- Status: Speicherort verschlüsselt = confirmed by user; Krypto-Contract, Rotations-Verzicht und Transport-Härtung = assumption (aus Grill-Findings G-002/G-003/G-010 abgeleitet) — flag if wrong.

**D4: Testmail über dedizierten Endpoint `POST /api/v1/mail-settings/test`.**
- Rationale: Verifikation der Konfiguration ohne fachlichen Use-Case; nutzt denselben `MailService`. Empfängeradresse als Request-Payload.
- Status: confirmed by user (Scope „Infrastruktur + Testmail").

**D5: Passwort-Update-Semantik: leeres Passwortfeld = unverändert.**
- Rationale: R4. Verhindert versehentliches Löschen und vermeidet, das Passwort je zum Frontend senden zu müssen.
- Status: assumption — flag if wrong.

## Assumptions

- **Assumption:** Ein Verschlüsselungsschlüssel wird als Env-Variable/Config bereitgestellt (`KIGRUAPP_MAIL_ENCRYPTION_KEY`), base64-kodierte 32 Byte. Es gibt **keinen mitgelieferten Default** (auch nicht für dev — Entwickler setzen die Variable explizit). Fehlt/ungültig, ist Speichern/Versand fail-closed deaktiviert mit klarer Meldung. Flag if wrong.
- **Assumption:** Key-Rotation wird nicht unterstützt; nach jedem Key-Wechsel muss das SMTP-Passwort neu eingetragen werden. Flag if wrong.
- **Assumption:** Testmail-Inhalt ist ein fixer, nicht-lokalisierter Platzhaltertext (z.B. Betreff „kigruapp Testmail"). Ausreichend für reine Verifikation.
- **Assumption:** Kein E-Mail-Templating-Engine in diesem Scope; `MailService` nimmt Klartext-Body entgegen. Qute wäre verfügbar, wird aber nicht eingeführt (nichts speculatives).
- **Assumption:** Deutsche UI-Labels (wie im restlichen Admin-Bereich), englische Code-Identifier (Projektkonvention, vgl. Board-Refactor).
- **Assumption:** Synchroner Versand im Request-Thread; kein Scheduler/Queue (existiert nicht, Scope erfordert es nicht).

## Component Responsibilities

- **`MailSettings` (Entity, Backend):** Singleton-Dokument mit host, port, encryption(enum), username, encryptedPassword, fromAddress, fromName, enabled. Besitzt die persistente Konfiguration. Singleton-Identität wird über eine **konstante, well-known `_id`** (fixer Upsert-Filter) erzwungen, sodass parallele PUTs dasselbe Dokument treffen und nie ein zweites `mail_settings`-Dokument entsteht (G-009).
- **`EncryptionService` (Backend, neu):** AES-GCM ver-/entschlüsseln mit Config-Schlüssel. Einziger Ort, der den Klartext kennt.
- **`MailService` (Backend, neu):** Baut aus (entschlüsselten) `MailSettings` eine `jakarta.mail`-Session und versendet eine Nachricht. Zentraler Versand-Einstiegspunkt für spätere Features. Prüft `enabled`/Vollständigkeit (R8).
- **`MailSettingsResource` (Backend, neu):** `/api/v1/mail-settings` — GET (ohne Passwort, mit `passwordSet`), PUT (Upsert, Passwort optional), POST `/test`. Admin-only durch Default-Deny (nicht whitelisten).
- **`MailSettingsService` (Frontend, neu):** Kapselt die drei Endpoints über `ApiService`.
- **`MailComponent` (Frontend, neu, `settings/mail/`):** Reactive-Form-Seite; lädt/speichert Einstellungen, löst Testmail aus, zeigt Ergebnisse.

## Interfaces

Backend (`/api/v1/mail-settings`, admin-only):
- `GET /` → `MailSettingsDto { host, port, encryption, username, fromAddress, fromName, enabled, passwordSet: boolean }` — **kein** Passwort.
- `PUT /` ← `MailSettingsUpdateDto { host, port, encryption, username, password?: string, clearPassword?: boolean, fromAddress, fromName, enabled }` → gespeichertes `MailSettingsDto`. `password` weggelassen/leer ⇒ bestehendes bleibt (R4/D5). `clearPassword:true` ⇒ gespeichertes Passwort wird entfernt (setzt sich nicht mit einem gleichzeitig gesetzten `password` — dann gilt das neue Passwort).
- `POST /test` ← `{ recipient: string }` → `{ success: boolean, category: string, message: string }`. `category` ∈ `{ OK, CONFIG_MISSING, AUTH_FAILED, CONNECTION_FAILED, UNKNOWN }`. `message` ist ein normalisierter, benutzerlesbarer Text pro Kategorie — **keine** rohe SMTP-Serverantwort.

Backend intern:
- `MailService.send(recipient: String, subject: String, body: String)` → wirft `MailException` (mit Kategorie) bei deaktiviert/unvollständig/fehlendem Key/SMTP-Fehler. Contract provisorisch (R7).

`encryption` enum: `NONE | STARTTLS | SSL_TLS`.

## Data Flow

Speichern:
1. Admin füllt Formular, klickt Speichern → Frontend `PUT /api/v1/mail-settings`.
2. `MailSettingsResource` validiert (R9); wenn `password` gesetzt → `EncryptionService.encrypt`.
3. Upsert des `MailSettings`-Singletons; Response ohne Passwort (`passwordSet` abgeleitet).

Testmail / Versand:
1. Admin klickt „Testmail senden" mit Empfänger → `POST /test`.
2. Resource lädt `MailSettings`, ruft `MailService.send(...)`.
3. `MailService` prüft Key-Verfügbarkeit, `enabled` und Vollständigkeit (R8; sonst `MailException` mit Kategorie), `EncryptionService.decrypt` des Passworts, baut `jakarta.mail`-Session gemäß `encryption` mit Timeouts (R11) und Transport-Härtung (R12), sendet.
4. Erfolg/Fehler wird geloggt und (für `/test`) auf eine Kategorie normalisiert als `{success, category, message}` zurückgegeben — immer HTTP 200 für Betriebsausgänge (G-008).

## Error Handling

- Validierungsfehler (R9) → 400 mit Feldhinweis.
- Fehlender/ungültiger Verschlüsselungsschlüssel: Speichern eines Passworts wird abgelehnt; der Test-Endpoint gibt `200 {success:false, category:CONFIG_MISSING}` zurück (nicht 500 — konsistent mit der Always-200-Regel unten). 500 ist ausschließlich unerwarteten Serverfehlern vorbehalten.
- `MailService` bei disabled/unvollständig/fehlendem Key → `MailException` mit Kategorie, gemappt auf `{success:false, category, message}` (Test) bzw. propagiert (interne Aufrufer).
- SMTP-Fehler (Auth, Timeout, Host nicht erreichbar) → gefangen, geloggt (serverseitig ausführlich), aber gegenüber dem Client auf eine **feste Kategorie + normalisierten Text** reduziert — keine rohe SMTP-Serverantwort und kein Stacktrace an den Client. Das begrenzt den in G-008 beschriebenen SSRF-/Port-Scan-Info-Oracle-Effekt (connection-refused vs. timeout vs. TLS-Banner sind nach außen nicht mehr unterscheidbar).
- Testmail-Endpoint gibt für alle Betriebsausgänge (inkl. fehlendem Key/disabled) `200 {success, category, message}` zurück — nicht 500 — damit die UI die Kategorie einfach anzeigt.

## Migration & Compatibility

- Greenfield: neue Entity/Collection `mail_settings`, initial nicht vorhanden. GET liefert leere/Default-Settings (`enabled:false`), bis ein Admin speichert. Kein Datenmigrations-Schritt nötig.
- Neue Config-Anforderung: `KIGRUAPP_MAIL_ENCRYPTION_KEY` (base64, 32 Byte) **muss** in jedem Deployment gesetzt werden — es gibt keinen mitgelieferten Default (auch nicht in `application.properties`); Entwickler setzen die Variable lokal explizit. Ohne gültigen Key ist das Mail-Feature deaktiviert (fail-closed). Rollback: Feature entfernen lässt die Collection unberührt.
- Key-Wechsel/-Rotation: nicht durch Migration abgedeckt (kein Key-Versioning). Ein geänderter oder verlorener Key macht gespeicherte Passwörter undechiffrierbar → der Admin muss das SMTP-Passwort neu eintragen. Als bewusste Betriebs-Einschränkung dokumentiert.

## Security Considerations

- **Neue Secret-Speicherung:** SMTP-Passwort verschlüsselt (AES-256-GCM, R10) at-rest; Schlüssel außerhalb der DB (Config/Env), kein Default (R3, fail-closed). Passwort wird nie an das Frontend gesendet (R3), auch nicht verschlüsselt.
- **Transit-Schutz:** Das entschlüsselte Passwort ist beim Versand exponiert; deshalb erzwingt die Session Zertifikats-Hostname-Prüfung und (im STARTTLS-Modus) verpflichtendes STARTTLS (R12) — verhindert MITM-Abgriff der Credentials.
- **Neue Outbound-Angriffsfläche:** Server öffnet SMTP-Verbindungen zu admin-konfigurierten Hosts. Nur Admins können Host/Port setzen → Risiko auf Admin-Vertrauensebene begrenzt. Zusätzlich normalisiert der Test-Endpoint Fehlerausgänge auf feste Kategorien (G-008), sodass er nicht als Port-Scan-/Internal-Host-Probe (SSRF-Oracle) missbraucht werden kann — akzeptiert, da admin-only.
- **Autorisierung:** alle Endpoints admin-only via Default-Deny; explizit **nicht** in `SecurityFilter` whitelisten.
- **Log-Hygiene:** Passwörter/Schlüssel nie loggen. Rohe SMTP-Serverantworten dürfen serverseitig geloggt, aber nicht an den Client zurückgegeben werden.

## Performance Considerations

- Geringe Last: Admin-Konfiguration selten; Versand synchron im Request-Thread. Damit ein langsamer/hängender SMTP-Host keine Worker-Threads dauerhaft blockiert, sind connect- und read-Timeout je 10s Pflicht (R11). Ein Concurrency-Cap für parallele Sends ist bei Single-Admin-Nutzung (nur der Testmail-Auslöser existiert in diesem Scope) nicht nötig — bewusst nicht eingeführt; erneut zu bewerten, sobald ein fachliches Feature Massen-/Parallelversand bringt. Erster echter Engpass wäre Massenversand — außerhalb dieses Scopes (kein Scheduler/Batch).

## Observability

- Quarkus-Logging (bestehend): Versandversuche und -fehler auf INFO/ERROR loggen (ohne Credentials). Keine Metrik-Infrastruktur vorhanden → keine Metriken in Scope. Test-Endpoint-Ergebnis ist die primäre Sichtbarkeit für Admins.

## Alternatives Considered

- **D1 — `Organisation`-Tag `"mail"` wiederverwenden:** Verworfen, weil der generische `OrganisationResource`-GET den (verschlüsselten) Passwort-Blob mit ausliefern würde und die Map-`entries` keine getrennte Behandlung des Secrets erlauben. Wiederverwendung würde R3 gefährden.
- **D2 — `quarkus-mailer` (SmallRye Mailer):** Idiomatisch für Quarkus und bietet mit `MockMailbox` + Dev-Mail-Capture ein starkes Test-/Dev-Werkzeug (das der Grill in G-005 zu Recht ins Feld führte). Trotzdem verworfen: die Konfiguration ist an Startup/`application.properties` gebunden, laufzeit-dynamische DB-Zugangsdaten sind damit umständlich — der zentrale Anforderungspunkt. Die verlorene Testbarkeit wird gezielt durch GreenMail als Test-Only-Dependency ersetzt (siehe D2), statt das Laufzeit-Config-Modell zu opfern.
- **D3 — Passwort nur in Env/Config (kein DB-Passwort):** Vom Nutzer verworfen (Admin soll Passwort per UI setzen).
- **D3 — Klartext in DB:** Vom Nutzer verworfen zugunsten Verschlüsselung.
- **Reversible/2-way Verschlüsselung vs. Hashing:** Hashing scheidet aus, da das Passwort im Klartext für den SMTP-Login gebraucht wird — daher reversible Verschlüsselung.

## Risks

- **Schlüssel-Management / Rotation:** Geht `KIGRUAPP_MAIL_ENCRYPTION_KEY` verloren **oder wird bewusst rotiert**, ist das gespeicherte Passwort nicht mehr entschlüsselbar → Admin muss es neu eingeben. Key-Versioning/Re-Encrypt ist bewusst nicht Teil des Scopes. Mitigation: dokumentierte Betriebs-Einschränkung; fail-closed mit klarer „Passwort neu eintragen"-Meldung bei Decrypt-Fehler.
- **Neue Mail-Dependency:** Explizit deklarieren statt sich auf transitive Artefakte zu verlassen, sonst Bruch bei Dependency-Updates. Mitigation: `pom.xml`-Eintrag.
- **SMTP-Blocking im Request-Thread:** Langsamer/hängender SMTP-Server blockiert den Request. Mitigation: connect-/read-Timeout je 10s als harte Anforderung (R11), nicht nur als Empfehlung.
- **Provisorischer MailService-Contract:** Die Signatur ist ohne echten Fach-Consumer geraten (G-006) und wird beim ersten Versand-Feature vermutlich angepasst. Mitigation: explizit als instabil markiert (R7); der Testmail-Endpoint ist der einzige aktuelle Consumer, an dem sie sich orientiert.
- **Credential-Abgriff in Transit / MITM:** entschärft durch R12 (Hostname-Prüfung, verpflichtendes STARTTLS).
- **SSRF-artige Fläche** (siehe Security) — entschärft durch normalisierte Fehlerkategorien (G-008); Restrisiko akzeptiert wegen admin-only.

## Non Goals

- Konkrete fachliche E-Mail-Anwendungsfälle (Willkommensmails, Benachrichtigungen, Massenversand an Eltern/Gruppen) und ihre Trigger.
- E-Mail-Templating/HTML-Mails, Mehrsprachigkeit der Mail-Inhalte.
- Asynchroner/geplanter Versand, Warteschlangen, Retry-Mechanik, Zustellstatus-Tracking, Concurrency-Begrenzung des Versands.
- Key-Rotation mit Re-Encrypt / Key-Versioning der gespeicherten Passwörter.
- Mehrere Mail-Profile/Konten (nur ein Singleton-Konfigurationssatz).
- Empfängerauswahl-UI.

## Open Questions

- Keine offen. (Verschlüsselungsschlüssel-Herkunft und Testmail-Inhalt sind als Assumptions festgehalten; falls falsch, dort flaggen.)

## Decision Log

- 2026-07-22 — Spec erstellt (Design-Interview: Scope = Infrastruktur+Testmail, Passwort = verschlüsselt in DB; übrige Entscheidungen aus Repo/Regeln abgeleitet). Trigger: forge-design.

## Changelog

- 2026-07-22 — resolves G-001: kein Default-Verschlüsselungsschlüssel; fehlt/ungültig ⇒ Feature fail-closed (R3 erweitert, Assumptions/Migration angepasst). Trigger: grill review.
- 2026-07-22 — resolves G-002: Krypto-Contract fixiert (base64-32B-Key, SecureRandom-Nonce, base64(iv‖ct‖tag)) als neues R10, D3 präzisiert. Trigger: grill review.
- 2026-07-22 — resolves G-003: Key-Rotation als nicht unterstützt deklariert und dokumentiert (Risks, Migration, Non-Goals, D3). Trigger: grill review.
- 2026-07-22 — resolves G-004: connect-/read-Timeout je 10s als hartes R11; Concurrency-Cap bewusst weggelassen (Performance/Non-Goals). Trigger: grill review.
- 2026-07-22 — resolves G-005: D2 bleibt; GreenMail als Test-Only-Dependency für R6/R7 ergänzt (Constraints, Success Criteria, Alternatives). Trigger: grill review.
- 2026-07-22 — resolves G-006: MailService-Contract explizit provisorisch/intern, gerechtfertigt durch Testmail als ersten Consumer (R7, Risks). Trigger: grill review.
- 2026-07-22 — resolves G-007: `clearPassword`-Flag zum Entfernen; Vollständigkeits-Definition inkl. No-Auth-Fall (R4, R8, Interfaces). Trigger: grill review.
- 2026-07-22 — resolves G-008: Test-Endpoint immer 200 mit `{success,category,message}`, normalisierte Fehlerkategorien statt SMTP-Banner (Error Handling, Interfaces, Security). Trigger: grill review.
- 2026-07-22 — resolves G-009: Singleton via konstante well-known `_id` (Component Responsibilities). Trigger: grill review.
- 2026-07-22 — resolves G-010: Transport-Härtung (checkserveridentity + starttls.required) als neues R12 (D3, Security). Trigger: grill review.
