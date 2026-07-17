# Authentifizierung + Rollen

**Datum:** 2026-06-13
**Status:** Approved

## Zusammenfassung

Keycloak-basierte Authentifizierung mit JWT-Token-Validierung im Backend (Quarkus OIDC) und `angular-oauth2-oidc` im Frontend. ADMIN-Rolle wird in der App gespeichert (Person.roles als FieldInstance), nicht in Keycloak. Zentraler SecurityFilter schuetzt alle API-Endpunkte mit Whitelist-Ansatz. Vorkonfigurierter Realm-Export fuer reproduzierbares Dev-Setup. Ersteinrichtungs-Modus statt Seed-Admin.

---

## 1. Backend Auth

### 1.1 Quarkus OIDC Integration

- `quarkus-oidc` Extension in `pom.xml`
- Alle `/api/v1/**` Endpunkte erfordern ein gueltiges Bearer Token (Ausnahme: Setup-Endpunkt)
- PKCE Flow (public client, kein Secret)
- Konfiguration in `application.properties`:
  - `quarkus.oidc.auth-server-url=http://keycloak:8443/realms/kigruapp`
  - `quarkus.oidc.client-id=kigruapp-frontend`
  - `quarkus.oidc.application-type=service`

### 1.2 Dev-Bypass (Profil `%dev`)

Fuer lokale Entwicklung ohne Keycloak:
- `%dev.quarkus.oidc.enabled=false`
- `%dev.quarkus.security.auth.enabled-in-dev-mode=false`
- `CurrentUserService` liefert im Dev-Mode eine konfigurierbare Person (z.B. erste Person mit ADMIN-Rolle aus der DB)
- Ermoeglicht Frontend-Entwicklung ohne Docker/Keycloak

### 1.3 CurrentUserService (neu)

Request-scoped Service der den eingeloggten User resolved:

1. **Dev-Mode:** Wenn OIDC deaktiviert, liefert erste Person mit ADMIN-Rolle aus der DB (oder null wenn DB leer)
2. **Prod-Mode:** Extrahiert `sub` (Keycloak User ID) aus dem JWT `SecurityIdentity`
3. Sucht Person mit `keycloakUserId == sub`
4. **Einmaliges Fallback:** Wenn keine Person mit `keycloakUserId` gefunden, matched ueber Email aus dem Token gegen Email-FieldInstance aller Personen. Setzt `keycloakUserId` permanent auf der gefundenen Person. Danach ist Email-Aenderung in Keycloak irrelevant.
5. Keine Person gefunden → 403 Forbidden
6. `isAdmin()` — prueft ob Person eine FieldInstance mit `fieldName: "role"` und `value: "ADMIN"` in `Person.roles` hat
7. Stellt bereit: `currentPerson`, `currentFamilyId`, `isAdmin()`

### 1.4 SecurityFilter (neu, JAX-RS ContainerRequestFilter)

Zentraler Filter mit **Whitelist-Ansatz** — alles ist per Default Admin-only:

| Pfad | Methode | Zugang |
|------|---------|--------|
| `/api/v1/setup` | POST | Nur wenn DB leer (keine Personen) |
| `/api/v1/cooking-duties` | GET | Alle authentifizierten |
| `/api/v1/cooking-duties/**` | PUT, DELETE | Eigene Familie oder Admin |
| `/api/v1/organisation/groups` | GET | Alle authentifizierten |
| `/api/v1/organisation/duty-settings` | GET | Alle authentifizierten |
| `/api/v1/organisation/**` | PUT | Admin-only |
| `/api/v1/field-instances` | POST, PUT, DELETE | Eigene Daten oder Admin |
| `/api/v1/field-instances/**` | GET | Alle authentifizierten |
| `/api/v1/persons/me` | GET | Alle authentifizierten |
| `/api/v1/persons/**` | alle anderen | Admin-only |
| `/api/v1/families/**` | alle | Admin-only |
| `/api/v1/field-definitions/**` | GET | Alle authentifizierten |
| `/api/v1/field-definitions/**` | POST, PUT, PATCH, DELETE | Admin-only |
| Alles andere | alle | Admin-only (safe default) |

Familiennzugehoerigkeits-Check fuer "eigene Familie oder Admin":
1. Ist der User Admin? → erlaubt
2. Gehoert die betroffene Resource zur Familie des Users? → erlaubt
3. Sonst → 403

### 1.5 `/api/v1/persons/me` Endpunkt (neu)

Neuer GET-Endpunkt in `PersonResource`:
- Nutzt `CurrentUserService` um eingeloggte Person zu identifizieren
- Gibt `PersonDTO` zurueck (mit resolved FieldInstances)
- Einziger Persons-Endpunkt der fuer normale User zugaenglich ist

### 1.6 `/api/v1/setup` Endpunkt (neu, Ersteinrichtung)

Einmaliger Setup-Endpunkt — funktioniert **nur wenn keine Personen in der DB existieren**:
- `GET /api/v1/setup/status` — gibt `{ required: true/false }` zurueck (kein Auth noetig)
- `POST /api/v1/setup` — erwartet Keycloak Token, erstellt:
  1. Neue Familie (Name aus Request)
  2. Neues Elternteil (Person) mit basicProperties aus dem Keycloak-Token (email, firstName, lastName)
  3. Setzt `keycloakUserId` aus dem Token
  4. Weist automatisch ADMIN-Rolle zu
- Gibt 403 zurueck sobald mindestens eine Person existiert
- Kein SecurityFilter noetig — Endpunkt prueft selbst

---

## 2. Frontend Auth

### 2.1 AuthService (reaktivieren)

`angular-oauth2-oidc` Stub wird durch echte Implementation ersetzt:
- OIDC Config: Keycloak Issuer URL, Client-ID `kigruapp-frontend`, PKCE Flow, response_type `code`
- Auto-Login: Redirect zu Keycloak wenn nicht authentifiziert
- Token Management: Access Token automatisch refreshen via silent refresh
- Bereitstellt: `accessToken`, `isAuthenticated`, `userName`, `userEmail`

### 2.2 AuthInterceptor (erweitern)

Bestehender `auth.interceptor.ts`:
- Haengt `Authorization: Bearer <token>` an jeden API-Call
- Bei 401 Response → Redirect zu Keycloak Login

### 2.3 AuthGuard (erweitern)

Bestehender `auth.guard.ts`:
- Prueft ob User authentifiziert ist (OAuth Token vorhanden)
- Neuer `adminGuard` — prueft ob User ADMIN-Rolle hat (via CurrentUserService)

### 2.4 CurrentUserService (neu, Frontend)

- Beim App-Start: `GET /api/v1/persons/me` → PersonDTO des eingeloggten Users
- Cached: `currentPerson`, `currentFamilyId`, `isAdmin`
- Wird von CookingComponent genutzt (familyParents laden, canEdit Pruefung)
- BehaviorSubject fuer reaktive Updates

### 2.5 Sidebar-Schutz

Admin-only Links werden via `@if` ausgeblendet:
- `/administration/families` — nur wenn `isAdmin`
- `/settings/*` (Organisation, Custom Fields, Permissions) — nur wenn `isAdmin`
- `/cooking` — immer sichtbar

### 2.6 Admin Route Guard

Routen `/administration/**` und `/settings/**` bekommen `adminGuard`:
- Prueft `CurrentUserService.isAdmin`
- Redirect zu `/cooking` wenn kein Admin

### 2.7 Setup-Wizard (Ersteinrichtung)

Einmaliger Einrichtungsmodus beim allerersten App-Start:

1. App startet, ruft `GET /api/v1/setup/status` auf
2. Wenn `required: true` → zeigt Setup-Seite statt normalem Login
3. Setup-Seite: "Willkommen bei KigruApp — Ersteinrichtung"
4. User klickt "Mit Keycloak anmelden" → Redirect zu Keycloak, legt dort Account an
5. Nach Login zurueck in der App: `POST /api/v1/setup` wird aufgerufen
6. Backend erstellt Familie + Person + ADMIN-Rolle aus Token-Daten
7. Redirect zu `/cooking` — App ist eingerichtet, Setup-Modus gesperrt

Route: `/setup` — nur erreichbar wenn `setup/status.required == true`

---

## 3. Keycloak Setup

### 3.1 Realm-Export

Datei: `infra/keycloak/kigruapp-realm.json`

Vorkonfigurierter Realm:
- **Realm:** `kigruapp`
- **Client:** `kigruapp-frontend` — public client, PKCE enabled, Standard Flow
- **Redirect URIs:** `http://localhost:4200/*`
- **Web Origins:** `http://localhost:4200`
- **Login Settings:** Email als Username, Self-Registration **aktiviert** (damit der erste User sich im Setup registrieren kann)
- **Kein vorgegebener User** — der erste User erstellt sich selbst im Setup-Wizard

### 3.2 docker-compose.yml (erweitern)

Keycloak Service importiert Realm beim Start:
```yaml
keycloak:
  image: quay.io/keycloak/keycloak:26.0
  command: start-dev --import-realm
  environment:
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: admin
  ports:
    - "8443:8080"
  volumes:
    - ./infra/keycloak/kigruapp-realm.json:/opt/keycloak/data/import/kigruapp-realm.json
```

### 3.3 Seed Migration

**FieldDefinitionSeedMigration** (erweitern):
- Neue FieldDefinition `role`: `{ fieldName: "role", label: { de: "Rolle" }, jsonSchema: { type: "string", enum: ["ADMIN", "PARENT"] } }`

Keine AdminSeedMigration — der erste Admin wird ueber den Setup-Wizard erstellt.

---

## 4. Dateien

### Neu erstellen

| Datei | Zweck |
|-------|-------|
| `backend/.../security/CurrentUserService.java` | Request-scoped User Resolution + Dev-Bypass |
| `backend/.../security/SecurityFilter.java` | Zentraler Auth/Authz Filter |
| `backend/.../resource/SetupResource.java` | Ersteinrichtungs-Endpunkt |
| `frontend/.../core/services/current-user.service.ts` | Frontend User State |
| `frontend/.../core/guards/admin.guard.ts` | Admin Route Guard |
| `frontend/.../setup/setup.component.ts` | Setup-Wizard UI |
| `frontend/.../setup/setup.component.html` | Setup-Wizard Template |
| `infra/keycloak/kigruapp-realm.json` | Keycloak Realm-Export |

### Bestehend aendern

| Datei | Aenderung |
|-------|-----------|
| `backend/pom.xml` | `quarkus-oidc` Extension |
| `backend/.../resources/application.properties` | OIDC Konfiguration + Dev-Bypass |
| `backend/.../resource/PersonResource.java` | `/me` Endpunkt |
| `backend/.../migration/FieldDefinitionSeedMigration.java` | `role` Definition |
| `frontend/.../core/services/auth.service.ts` | OAuth2 Implementation statt Stub |
| `frontend/.../core/interceptors/auth.interceptor.ts` | Bearer Token + 401 Handling |
| `frontend/.../core/guards/auth.guard.ts` | Echte Auth-Pruefung |
| `frontend/.../app.component.html` | Admin-only Sidebar Links |
| `frontend/.../app.routes.ts` | adminGuard auf Admin-Routen + `/setup` Route |
| `frontend/.../cooking/cooking.component.ts` | CurrentUserService Integration |
| `docker-compose.yml` | Keycloak Realm-Import Volume |
