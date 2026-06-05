# KigruApp — Stammdaten (Familienverwaltung) Design Spec

**Date:** 2026-06-05
**Status:** Approved
**Scope:** Stammdaten-Modul — Familien, Kinder, Eltern, Custom Fields

---

## 1. Überblick

KigruApp ist eine Self-Hosted-Anwendung für elternverwaltete Kindergruppen. Sie läuft als Docker-Compose-Setup (z.B. auf einem Raspberry Pi) und bildet die Verwaltungs-Workflows einer Kindergruppe ab.

Dieses Spec beschreibt das erste Modul: **Stammdaten** — die Verwaltung von Familien, Kindern und Elternteilen inkl. dynamischer Custom Fields.

**Referenz:** Oracle APEX App "Kirschbaumhaus" (Bereich Stammdaten → Adressen)

---

## 2. Tech-Stack

| Komponente     | Technologie                          |
|----------------|--------------------------------------|
| Backend        | Quarkus (Java) + REST API            |
| Frontend       | Angular + Angular Material           |
| Datenbank      | MongoDB                              |
| Auth           | Keycloak (eigener Container)         |
| Reverse Proxy  | Nginx                                |
| Deployment     | docker-compose (4 Container)         |
| i18n           | Angular `@angular/localize`          |

---

## 3. Container-Architektur

```
┌─────────────────────────────────────────────────┐
│  docker-compose                                 │
│                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐     │
│  │  Nginx   │  │ Quarkus  │  │ MongoDB  │     │
│  │ (Angular)│──│  (API)   │──│          │     │
│  │ :80/:443 │  │ :8080    │  │ :27017   │     │
│  └──────────┘  └──────────┘  └──────────┘     │
│                ┌──────────┐                     │
│                │ Keycloak │                     │
│                │ :8443    │                     │
│                └──────────┘                     │
└─────────────────────────────────────────────────┘
```

- **Nur Nginx ist nach außen exponiert** (Port 80/443)
- Quarkus, MongoDB, Keycloak sind nur im internen Docker-Netzwerk erreichbar
- Nginx dient als Reverse Proxy:
  - `/` → Angular SPA (statische Files)
  - `/api/*` → Quarkus
  - `/auth/*` → Keycloak

---

## 4. Datenmodell (MongoDB)

### families
```json
{
  "_id": "ObjectId",
  "name": "string",
  "createdAt": "datetime",
  "customFields": {}
}
```

### children
```json
{
  "_id": "ObjectId",
  "familyId": "ObjectId",
  "firstName": "string (required)",
  "lastName": "string (required)",
  "dateOfBirth": "date (required)",
  "gender": "string (required)",
  "entryDate": "date",
  "exitDate": "date",
  "notes": "string",
  "customFields": {}
}
```

### parents
```json
{
  "_id": "ObjectId",
  "familyId": "ObjectId",
  "firstName": "string (required)",
  "lastName": "string (required)",
  "email": "string",
  "phone": "string",
  "address": {
    "street": "string",
    "zip": "string",
    "city": "string"
  },
  "keycloakUserId": "string",
  "permissions": ["string"],
  "customFields": {}
}
```

### field_definitions
```json
{
  "_id": "ObjectId",
  "entity": "child | parent | family",
  "fieldName": "string",
  "label": { "de": "string", "en": "string" },
  "type": "text | date | select | boolean",
  "options": ["string"],
  "required": "boolean"
}
```

Jeder Entity-Typ hat ein `customFields`-Objekt, dessen Struktur durch `field_definitions` bestimmt wird. Neue Felder werden in der Settings-Sektion angelegt und erscheinen automatisch in den Formularen.

---

## 5. REST API

```
/api/v1/
  /families              GET (Liste), POST
  /families/{id}         GET, PUT, DELETE
  /families/{id}/children    GET
  /families/{id}/parents     GET

  /children              GET (alle), POST
  /children/{id}         GET, PUT, DELETE

  /parents               GET (alle), POST
  /parents/{id}          GET, PUT, DELETE

  /field-definitions     GET, POST, PUT, DELETE
```

- Alle Endpoints via Keycloak OIDC geschützt
- Berechtigungsprüfung über `permissions` am Parent-Objekt
- **Wizards sind reine Frontend-Konzepte** — sie sammeln Daten über mehrere Schritte und nutzen die bestehenden CRUD-Endpoints. Kein eigener Wizard-Endpoint nötig.

---

## 6. Angular Frontend

### Projektstruktur

```
/app
  /core              — Auth-Service, HTTP-Interceptor, Guards
  /shared            — Pipes, Direktiven, i18n
  /administration
    /families        — Übersichtsliste, Detail-Ansicht, Wizard
  /settings
    /custom-fields   — Custom Fields verwalten
    /permissions     — Berechtigungen vergeben
```

### Familien-Übersicht

- `MatTable` mit Spalten: Typ (Kind/Elternteil), Name, Email, Telefon, Strasse, PLZ, Ort, Geburtsdatum, Familie, Austrittsdatum
- Sortierbar, filterbar
- Button "Kind erstellen" öffnet Wizard als `MatDialog`

### Wizard "Kind erstellen" (MatStepper in MatDialog)

3-Schritt-Wizard:

1. **Familie** — Radio-Auswahl:
   - "Neue Familie erstellen"
   - "Bestehende Familie verwenden" (Dropdown mit bestehenden Familien für Geschwisterkinder)

2. **Kind** — Formular mit:
   - Pflichtfelder: Vorname, Nachname, Geburtsdatum, Geschlecht
   - Optionale Felder: Eintrittsdatum, Notizen
   - Dynamische Custom Fields aus `field_definitions`

3. **Eltern** — Dynamische Sektionen:
   - Button "Elternteil hinzufügen" fügt neue Formular-Sektion ein
   - Pro Elternteil: Vorname, Nachname, Telefon, Email, Adresse (Strasse, PLZ, Ort)
   - Ab dem 2. Elternteil: Checkbox "Adresse wiederverwenden"
   - Dynamische Custom Fields

**Navigation:** Zurück/Abbrechen jederzeit möglich, Wizard-State bleibt erhalten.
**Abschluss:** Button "Familie erstellen" ruft sequentiell die CRUD-Endpoints auf: `POST /families` → `POST /children` → `POST /parents` (pro Elternteil).

---

## 7. Keycloak-Integration

- **Ein Realm pro Kindergruppe** (z.B. "kirschbaumhaus")
- Realm wird beim ersten Start automatisch erstellt via Keycloak Realm-Import (JSON-Config)
- **User-Provisioning:** Beim Anlegen eines Elternteils im Wizard erstellt Quarkus via Keycloak Admin API einen User. Das Elternteil erhält eine E-Mail mit Passwort-setzen-Link.
- **Authentifizierung:** Keycloak (Login, Token, Refresh)
- **Autorisierung:** App-seitig über `permissions`-Array am Parent-Objekt in MongoDB (nicht Keycloak-Rollen)
- **Angular:** `angular-oauth2-oidc` Library für Login-Flow, Token-Refresh, Guards

---

## 8. Berechtigungen

Keine festen Rollen. Granulare Berechtigungen pro Elternteil vergeben:

- Vorstandsmitglieder = Eltern mit erweiterten Permissions
- Berechtigungen werden in der Settings-Sektion verwaltet
- Beispiel-Permissions: `families.read`, `families.write`, `settings.admin`, `permissions.manage`

---

## 9. i18n

- **Frontend:** `@angular/localize` mit Übersetzungsdateien (`messages.de.json`, `messages.en.json`)
- **Deutsch als Default**
- **Custom Fields:** Labels mehrsprachig in `field_definitions` (`label: { de: "...", en: "..." }`)
- **Backend:** Kein i18n — API liefert Daten, Frontend übersetzt

---

## 10. Responsive Design

- Angular Material Komponenten sind von Haus aus responsive
- Mobile-first: Tabelle wird auf kleinen Screens zu Karten-Layout oder horizontalem Scroll
- Wizard-Dialog passt sich an Bildschirmgröße an

---

## 11. Scope-Abgrenzung

### In Scope (dieses Spec)
- Familien-Übersichtsliste
- Wizard "Kind erstellen" (Familie + Kind + Eltern)
- Custom Fields Verwaltung
- Berechtigungsverwaltung
- Docker-Compose Setup (4 Container)
- Keycloak Realm + User-Provisioning
- i18n-Grundstruktur

### Nicht in Scope (spätere Module)
- Kostenabrechnung
- Elterndienste (Kochen, Putzen, Springerdienste)
- Termine / Freie Tage
- Schuljahr-Verwaltung
- Downloads
- Home-Dashboard
