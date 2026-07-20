# Kosten-Definitionen & Kosten pro Semester — Design Spec

**Datum:** 2026-07-20
**Status:** Entwurf

---

## Überblick

Admins können in der Organisation Kosten-Feld-Definitionen anlegen (z.B. Label "Elternbeitrag" mit Währung "EUR €"). Diese Definitionen sind global und semester-/gruppenübergreifend — analog zu Gruppen/Teams/Rollen. In einer neuen Seite "Kosten pro Semester" weisen Admins den Definitionen konkrete Beträge zu — und zwar pro **Semester UND Gruppe** (z.B. 2025/2026, Gruppe "Bären", Elternbeitrag: 340 €). Jede Definition gilt einheitlich als gruppenabhängig — es gibt keine Unterscheidung zwischen gruppenspezifischen und globalen Definitionen.

Zusätzlich wird eine admin-verwaltete Währungsliste (Code + Symbol) eingeführt, aus der beim Anlegen einer Kosten-Definition gewählt wird — es gibt noch kein Währungskonzept im Projekt.

**Gruppen-Konzept:** Es gibt keine dedizierte `Group`-Entity im Backend — Gruppen sind `FieldInstance`-Datensätze unter dem Tag `"groups"` (generisches Tag-basiertes Feldsystem, siehe `OrganisationResource`/`FieldInstanceResource`). Im Frontend werden sie über `OrganisationService.getByTag('groups')` geladen (gleiches Muster wie in Platzzuweisung). `KostenValue` referenziert daher eine `groupId` als `FieldInstance`-ID, kein eigener Entity-Typ.

---

## Datenmodell

### Neue Entity: `Currency`

Collection `currencies`.

```java
public class Currency extends PanacheMongoEntity {
    public String code;    // z.B. "EUR"
    public String symbol;  // z.B. "€"
}
```

### Neue Entity: `KostenDefinition`

Collection `kosten_definitions`.

```java
public class KostenDefinition extends PanacheMongoEntity {
    public String label;        // z.B. "Elternbeitrag"
    public ObjectId currencyId; // Referenz auf Currency
    public boolean active;      // Default true bei Anlage
}
```

Definitionen werden nicht gelöscht, sondern **deaktiviert**. Eine inaktive Definition kann nicht mehr für neue Werte verwendet werden, bleibt aber inkl. ihrer bereits erfassten historischen `KostenValue`-Zeilen erhalten und in der Liste sichtbar (als "inaktiv" markiert).

### Neue Entity: `KostenValue`

Collection `kosten_values`. Speichert den konkreten Betrag einer Definition für ein Semester **und eine Gruppe**.

```java
public class KostenValue extends PanacheMongoEntity {
    public ObjectId semesterId;
    public ObjectId groupId;    // FieldInstance-ID (Tag "groups")
    public ObjectId definitionId;
    public BigDecimal amount;
}
```

Pro `(semesterId, groupId, definitionId)` existiert maximal eine Zeile (Upsert-Semantik). Ein Wert ist optional — nicht jede Definition muss für jede Semester/Gruppen-Kombination einen Wert haben.

---

## Backend

### Neu: `CurrencyResource`

- `GET /api/v1/currencies` — Liste aller Währungen.
- `POST /api/v1/currencies` `{ code, symbol }` — legt eine neue Währung an.

Kein eigener Delete-Endpunkt in dieser Version — es gibt keine eigene Verwaltungsoberfläche für Währungen, sie werden nur inline beim Anlegen einer Kosten-Definition erzeugt (siehe Frontend).

### Neu: `KostenDefinitionResource`

- `GET /api/v1/kosten-definitions` — Liste aller Definitionen (inkl. aufgelöster Währung: Code + Symbol, inkl. `active`-Flag).
- `POST /api/v1/kosten-definitions` `{ label, currencyId }` — legt eine neue Definition an (`active = true`).
- `PATCH /api/v1/kosten-definitions/{id}/active` `{ active }` — (de-)aktiviert eine Definition. Kein Lösch-Endpunkt in dieser Version.

### Neu: `KostenValueResource`

- `GET /api/v1/kosten-values?semesterId=...&groupId=...` — Liste aller Werte für eine Semester/Gruppen-Kombination (eine Zeile je `KostenDefinition`, `amount` ggf. `null` falls noch nicht gesetzt).
- `PUT /api/v1/kosten-values` `{ semesterId, groupId, definitionId, amount }` — Upsert eines einzelnen Werts.

Alle drei Resources folgen dem bestehenden Muster (siehe `SemesterResource`): dünne JAX-RS-Klasse, statische Panache-Finder, manuelles DTO-Mapping. Standardmäßig admin-only über den bestehenden `SecurityFilter` (kein Whitelist-Eintrag nötig).

---

## Frontend

### Neuer Tab "Kosten-Definitionen" in Organisation-Settings

`organisation.component.html`/`.ts` bekommt einen neuen `mat-tab` "Kosten-Definitionen", analog zum bestehenden Semester-Tab. Kein eigener Währungs-Tab — die Währungsverwaltung ist in dieses Formular integriert:

- Formular: Label (Text), Währung (`mat-select`, befüllt aus `CurrencyService.getAll()`, Anzeige z.B. "EUR €") → `POST /kosten-definitions`.
  - Inline-Option im Währungs-`mat-select`, um eine neue Währung anzulegen (z.B. "+ neue Währung" öffnet zwei kleine Felder Code/Symbol direkt im Dropdown-Panel oder ein kompaktes Inline-Formular darunter) → `POST /currencies`, danach automatisch in der Auswahl vorausgewählt.
- Tabelle darunter: Spalten Label, Währung, Status (Aktiv/Inaktiv), Toggle-Button ("Deaktivieren"/"Aktivieren" statt Lösch-Button).

Neuer `CurrencyService` in `shared/services`: `getAll()`, `create(code, symbol)`.
Neuer `KostenDefinitionService`: `getAll()`, `create(label, currencyId)`, `setActive(id, active)`.

### Neue Seite "Kosten pro Semester" (unter Administration, neben Platzzuweisung)

- Zwei `mat-select`-Dropdowns nebeneinander:
  - **Semester**, gleiches Muster wie Platzzuweisung/Elterneinteilung (befüllt aus `SemesterService.getAll()`, vorausgewählt der erste/zuletzt angelegte Eintrag).
  - **Gruppe**, befüllt aus `OrganisationService.getByTag('groups')` (gleiches Muster wie in Platzzuweisung/Elterneinteilung), vorausgewählt die erste Gruppe.
- Tabelle mit einer Zeile je **aktiver** `KostenDefinition` (Spalten: Label, Währungssymbol, Betrag-Eingabefeld). Inaktive Definitionen werden hier nicht angezeigt, auch nicht rückwirkend für Semester, in denen sie bereits einen Wert hatten (historische Werte inaktiver Definitionen bleiben in der DB erhalten, sind aber über diese Seite nicht mehr einsehbar — siehe Randfälle). Beträge werden beim Laden aus `GET /kosten-values?semesterId=...&groupId=...` vorbefüllt, leer falls noch kein Wert existiert.
- Wechsel des Semesters oder der Gruppe im jeweiligen Dropdown lädt die Werte neu.
- Speichern pro Zeile (oder gesammelt) ruft `PUT /kosten-values` auf.

Neuer `KostenValueService`: `getForSemesterAndGroup(semesterId, groupId)`, `upsert(semesterId, groupId, definitionId, amount)`.

Route unter dem bestehenden `administration`-Parent (adminGuard), lazy-loaded.

---

## Randfälle

- **Kosten-Definition mit bestehenden Werten "loswerden":** Es gibt kein Löschen. Der Admin deaktiviert die Definition stattdessen (`active = false`) — sie verschwindet aus der "Kosten pro Semester"-Seite und kann nicht mehr für neue Werte ausgewählt werden, bleibt aber inkl. ihrer historischen `KostenValue`-Zeilen in der Datenbank und in der Definitionen-Liste (als inaktiv markiert) erhalten. Reaktivierung ist jederzeit möglich (`active = true`).
- **Neues Semester bzw. neue Gruppe ohne Werte:** Tabelle in "Kosten pro Semester" zeigt alle Definitionen mit leeren Beträgen — kein Sonderfall im Backend nötig (fehlende `KostenValue`-Zeile = kein Wert).
- **Gruppe wird in Organisation-Settings gelöscht, während `KostenValue`-Zeilen darauf verweisen:** analog zu `SemesterAssignment` (siehe Semester-Design) — dangling `groupId`-Refs werden beim Auflösen ignoriert; die gelöschte Gruppe verschwindet einfach aus dem Gruppen-Dropdown, ihre historischen `KostenValue`-Zeilen bleiben ungenutzt in der DB (kein aktives Cleanup).
- **Betragsformat:** `BigDecimal`, zweistellig gerundet in der Anzeige (z.B. "340,00 €"); keine Validierung auf negative Werte in dieser Version (nicht im Scope).

---

## Nicht im Scope

- Bearbeiten von Kosten-Definitionen (nur Anlegen/Aktivieren/Deaktivieren, kein Rename/Währungswechsel nach Anlage).
- Löschen von Kosten-Definitionen (nur Deaktivieren, siehe Randfälle).
- Eigene Verwaltungsoberfläche für Währungen (Bearbeiten/Löschen) — Währungen werden nur inline angelegt.
- Automatische Übernahme von Beträgen aus dem Vorsemester beim Anlegen eines neuen Semesters.
- Verknüpfung der Kosten-Werte mit tatsächlichen Zahlungen/Buchhaltung — dies ist reine Stammdaten-Erfassung.
- Zuweisung von Kosten zu einzelnen Kindern/Familien (Werte gelten pauschal pro Semester+Gruppe, nicht pro Person).
- Definitionen, die wahlweise gruppen-spezifisch oder global sein können — jede Definition ist immer pro Gruppe erfasst.
