# Stundenerfassung — Design-Spezifikation

**Datum:** 2026-07-28
**Status:** Freigegeben (Design), Implementierungsplan ausstehend

## 1. Ziel & Scope

Eltern erfassen ihre geleisteten Freiwilligen-Stunden selbst. Zwei Bereiche:

- **Eltern-Eingabe** — für **alle** Eltern zugänglich (auch nicht-Admins). Master-Detail-UI (Liste links, Formular rechts) nach dem Vorbild von `mail-template-editor`. Eltern erfassen, bearbeiten und löschen **ihre eigenen** Einträge.
- **Admin-Übersicht** — eigene Admin-Sektion („Stundenübersicht"). Pro Elternteil summiert, aufklappbar zu den Einzeleinträgen. Admins dürfen jeden Eintrag bearbeiten und löschen.

Nicht im Scope: Export/Reporting über die Summenansicht hinaus, Genehmigungs-/Freigabe-Workflow, Benachrichtigungen.

## 2. Kontext (Ist-Zustand)

- **Backend:** Quarkus + MongoDB Panache, Package `at.kigruapp`.
- Eltern = `Person` (`persons`), Identität über `keycloakUserId`; aktueller User via `CurrentUserService.getCurrentPerson()`.
- Rollen/Teams sind `field_instances` unter `Organisation`-Dokumenten. Die Zuweisung „Person X hat Rolle Y in Semester Z" liegt in der Collection `semesterAssignments` (`section="role"`, `fieldInstanceId` = Rollen-Instanz, `semesterId`).
- „Aktives Semester" = neuestes Semester nach `createdAt`. Serverseitig aufgelöst nach dem Muster `PersonResource.resolveSemesterId(semesterIdParam)` (Param → sonst `Semester.listAll(Sort.descending("createdAt")).get(0)`).
- **Frontend:** Angular + Angular Material. Master-Detail-Vorbild: `frontend/src/app/settings/mail/mail-template-editor/`. HTTP über `ApiService` (`/api/v1`). Feedback über `NotificationService` (MatSnackBar). Routing in `app.routes.ts`; Nav-Menü in `app.component.html` (Admin-Links in `@if (currentUser.isAdmin)`, „Kochen" als Beispiel für einen Nicht-Admin-Link).

## 3. Datenmodell

Neue MongoDB-Collection `hourEntries`, Entity `HourEntry` (Panache-Stil wie `Family`).

| Feld | Typ | Zweck |
|---|---|---|
| `id` | ObjectId | Primärschlüssel (Panache) |
| `personId` | ObjectId | Wer den Eintrag erfasst hat |
| `semesterId` | ObjectId | Beim Anlegen an das aktive Semester gebunden |
| `roleFieldInstanceId` | ObjectId, **nullable** | Gewählte Rolle; `null` = „Kochen" |
| `roleDefinitionId` | ObjectId, nullable | FieldRef-Konsistenz zur Rollen-Definition |
| `roleLabel` | String | **Snapshot** des Rollen-Labels zum Erfassungszeitpunkt (bleibt stabil, auch wenn die Rolle später umbenannt oder entzogen wird). Für den Koch-Fall: `"Kochen"` |
| `date` | String `YYYY-MM-DD` | Tätigkeitsdatum (String wie `SemesterAssignment.entryDate`, timezone-sicher) |
| `minutes` | int | Dauer als Gesamtminuten (aus HH:MM geparst) |
| `comment` | String, optional | Freitext-Kommentar |
| `createdAt` | Instant | |
| `updatedAt` | Instant | |

**„Kochen"** ist eine fixe Zusatzoption, die **allen** Eltern im Dropdown angeboten wird (unabhängig von einer Koch-Zuweisung). Gespeichert als `roleFieldInstanceId=null` + `roleLabel="Kochen"`. Ein `null`-`roleFieldInstanceId` bedeutet eindeutig „Kochen".

### Bewusste Festlegungen

- **Eltern-Liste zeigt alle eigenen Einträge semesterübergreifend** (nicht nur aktives Semester). Nur das Rollen-**Dropdown** ist auf das aktive Semester beschränkt.
- **`semesterId` wird beim Anlegen fixiert** (aktives Semester zum Erstellzeitpunkt), auch wenn das gewählte Datum in ein anderes Semester fällt. Beim Bearbeiten bleibt `semesterId` unverändert.
- **Kommentar ist optional.**
- `roleLabel` wird als Snapshot gespeichert, damit Liste und Admin-Übersicht stabil bleiben, wenn eine Rolle nachträglich geändert wird.

## 4. Backend-API

`HourEntryResource`, Basis-Pfad `/api/v1/hour-entries`.

| Methode | Pfad | Zugriff | Zweck |
|---|---|---|---|
| `GET` | `/me` | angemeldet | Eigene Einträge, alle Semester, neueste zuerst → Eltern-Liste |
| `GET` | `/role-options?semesterId=` | angemeldet | Dropdown-Optionen: zugewiesene Rollen des (aktiven) Semesters via `semesterAssignments` + synthetische „Kochen"-Option |
| `POST` | `/` | angemeldet | Eigener Eintrag; `personId`=aktueller User, `semesterId`=aktiv aufgelöst |
| `PUT` | `/{id}` | Eigentümer **oder** Admin | Eintrag ändern |
| `DELETE` | `/{id}` | Eigentümer **oder** Admin | Eintrag löschen |
| `GET` | `/summary?semesterId=` | **admin-only** | Pro Elternteil summiert: `[{personId, name, totalMinutes, entries[]}]` |

**Berechtigung:** `PUT`/`DELETE` prüfen, ob der aktuelle User Eigentümer (`personId == currentPerson.id`) **oder** Admin (`CurrentUserService.isAdmin()`) ist; andernfalls 403. `GET /summary` ist admin-only. Fehler werden über den bestehenden `ExceptionMapper` gemeldet.

**DTOs:**
- `HourEntryDto` — Read: `id, personId, semesterId, roleFieldInstanceId, roleLabel, date, minutes, comment`.
- `HourEntrySaveDto` — Write, validiert: `roleFieldInstanceId?` (null = Kochen), `roleLabel` bzw. Rollen-Referenz, `date` (Pflicht, `YYYY-MM-DD`), `minutes` (Pflicht, > 0), `comment?`.
- `HourSummaryDto` — Admin: `personId, name, totalMinutes, entries: HourEntryDto[]`.
- `RoleOptionDto` — `fieldInstanceId?, definitionId?, label` (Kochen: `fieldInstanceId=null, label="Kochen"`).

## 5. Eltern-UI

Neue Standalone-Component `stunden` (Trio `.ts`/`.html`/`.scss`), Vorbild `mail-template-editor`.

- **Route:** `/stunden`, `canActivate: [authGuard]` (kein `adminGuard`).
- **Nav-Link:** in `app.component.html` **außerhalb** des `@if (currentUser.isAdmin)`-Blocks (wie „Kochen").
- **Layout:** CSS-Grid `264px minmax(0,1fr)`; links `<aside>`-Liste, rechts Reactive-Form (nur bei `editing` sichtbar), Placeholder-Empty-State sonst.

**Formularfelder (rechts):**
- **Rolle:** `mat-select`, Optionen = Rollen des aktiven Semesters + „Kochen". Pflicht.
- **Datum:** `MatDatepicker`, Anzeige `DD.MM.YYYY`. Pflicht. Erfordert `provideNativeDateAdapter` + Locale `de-AT` (im Mail-Bereich aktuell nicht genutzt → neu einzurichten). Persistiert als `YYYY-MM-DD`.
- **Zeit:** Textfeld `HH:MM` mit Pattern-Validierung `^\d{1,2}:[0-5]\d$` und `> 0`. Konvertierung HH:MM ↔ `minutes` beim Speichern/Laden.
- **Kommentar:** `mat-input`, optional.
- Speichern (create vs. update je nach `selectedId`) und Löschen mit `NotificationService`-Snackbar.

**Liste (links):** alle eigenen Einträge, neueste zuerst. Kurzform pro Item: **`DD.MM.YYYY – Rolle`** (z.B. `28.07.2026 – Kochen`). Klick lädt den Eintrag in den Editor; „Neu"-Button; Löschen-Icon pro Item.

**Service:** `hour-entry.service.ts` (`ApiService`): `listMine()`, `roleOptions(semesterId?)`, `create(req)`, `update(id, req)`, `delete(id)`, `summary(semesterId)`. Modelle in `hour-entry.model.ts`.

## 6. Admin-UI

Neue Component „Stundenübersicht" in eigener Admin-Sektion.

- **Route:** eigener Admin-Pfad, `canActivate: [authGuard, adminGuard]`. Nav-Link im Admin-`@if`-Block.
- **Semester-Auswahl:** `mat-select`, Default = neuestes Semester (Muster wie `platzzuweisung.component`).
- **Darstellung:** Liste der Eltern mit **Gesamtsumme** (formatiert HH:MM); je Elternteil `mat-expansion-panel`, aufgeklappt die Einzeleinträge (Datum, Rolle, Dauer, Kommentar).
- **Bearbeiten/Löschen** pro Einzeleintrag → wiederverwendetes Editor-Formular gegen dieselben `PUT`/`DELETE`-Endpunkte (Admin-berechtigt).

## 7. Fehlerbehandlung

- Backend: Validierungs- und Autorisierungsfehler über bestehenden `ExceptionMapper` (403 bei fremdem Eintrag, 400 bei ungültigem Payload).
- Frontend: `NotificationService.success/error` (MatSnackBar) nach Save/Delete, `extractError` für Server-Meldungen — analog Mail-Editoren.

## 8. Tests

**Backend (`HourEntryResource`-Tests, Muster wie bestehende Resource-Tests):**
- CRUD: Anlegen setzt `personId`/`semesterId` korrekt; Read `/me` liefert nur eigene Einträge, neueste zuerst.
- Ownership-Guard: Elternteil A darf Eintrag von B **nicht** ändern/löschen (403); Admin darf.
- `role-options` liefert die Rollen des aktiven Semesters **plus** „Kochen".
- `summary` aggregiert `totalMinutes` je Elternteil korrekt; admin-only (403 für Nicht-Admin).

**Frontend (Component-Specs, Muster `mail-template-editor.spec`):**
- Liste laden, Neu/Edit/Save/Delete-Flows.
- HH:MM-Validierung (ungültige Eingaben abgelehnt) und HH:MM ↔ `minutes`-Konvertierung.
- Kurzform-Rendering `DD.MM.YYYY – Rolle`.
