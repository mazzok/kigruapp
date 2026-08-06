# Design: Kochdienst-Erstellung durch Eltern — Fixes

## Ausgangslage

Anfrage des Users: beim Anlegen eines Kochdienstes als Elternteil sollen (1) im "Wer kocht"-Dropdown alle Elternteile der eigenen Familie wählbar sein, (2) mehrere Gruppen per Multi-Selektion wählbar sein, (3) ein neu angelegter Eintrag automatisch im Kochkalender erscheinen, (4) Kochdienste innerhalb von Schließzeiten nicht anlegbar sein, und (5) beim Hoovern über potentiellen Schließtagen die Art des Schließtags angezeigt werden.

Eine Codebase-Untersuchung ergab, dass (2), (3) und (4) bereits vollständig implementiert sind:

- **Gruppen-Multi-Selektion**: `CookingDutyDialogComponent` hat bereits Checkboxen pro Gruppe (`cooking-duty-dialog.component.html:13-21`), das Datenmodell (`CookingDutyDTO.groups: string[]`) unterstützt mehrere Gruppen pro Eintrag.
- **Auto-Refresh im Kalender**: `CookingComponent.createCookingDuty()` ruft nach dem Anlegen `loadDuties()` auf (`cooking.component.ts:275-277`) — neue Einträge erscheinen automatisch, sofern sie in den Monat/Filter der aktuellen Ansicht fallen.
- **Sperre während Schließzeiten**: `ClosureGuard.rejectIfClosed(...)` blockiert Kochdienst-Anlage/-Änderung serverseitig bereits mit 409 (`FieldInstanceResource.java:99,134`), das Frontend deaktiviert geschlossene Tage zusätzlich im Datepicker.

Zwei Punkte sind tatsächlich Lücken und Gegenstand dieses Designs:

- **(1) "Wer kocht"-Dropdown ist leer/unbrauchbar** — echter Bug.
- **(5) Kein Hover-Tooltip mit Schließtage-Art** — fehlendes Feature.

## A) "Wer kocht"-Dropdown

### Root Cause

`CookingComponent.loadOrganisationData()` lädt Personen der Familie über `PersonService.list(familyId)` → `GET /api/v1/persons?familyId=`. Zwei Probleme:

1. **Keine Rollen-Filterung**: Der Endpoint liefert alle Personen der Familie (Kinder und Eltern), es gibt keine Filterung auf `personType == PARENT`, obwohl der Query-Parameter `personType` in `PersonResource.list()` bereits deklariert (aber nirgends ausgewertet) ist.
2. **Typ-Mismatch**: `PersonService.list()` liefert das rohe `Person`-Modell (`basicProperties: FieldRef[]`, unaufgelöste Referenzen). Der Dialog erwartet aber `PersonDTO` (`basicProperties: FieldInstanceDTO[]`, aufgelöst) und wird per `as unknown as PersonDTO[]` erzwungen. `CookingDutyDialogComponent.getParentName()` sucht `basicProperties.find(f => f.fieldName === 'lastName')` — das matcht auf rohen `FieldRef`-Objekten nie, jede Option zeigt einen leeren Namen.

### Lösung

Neuer, schlanker Endpoint statt Änderung des bestehenden `/persons`-Endpoints — dieser wird von mehreren Admin-Komponenten (`family-wizard`, `board`, `elterneinteilung`) mit der bisherigen rohen `Person[]`-Form konsumiert und darf nicht brechen.

**Backend** (`PersonResource.java`):

```
GET /api/v1/persons/parents?familyId=<id>
→ List<PersonSummaryDTO>
```

- Neues DTO `PersonSummaryDTO { String id, String firstName, String lastName }`.
- Implementierung: `Person.findByFamilyId(familyId)` → Filterung auf `PARENT` via bestehenden `PersonLookupService.filterByPersonType(persons, "PARENT")` (Batch, kein N+1) → für die Treffer nur `firstName`/`lastName` aus `basicProperties` auflösen (kein `toFullDTO`, das zusätzlich Schedules/Duties/Finance/Custom-Properties/Semester-Assignments auflöst — für ein Dropdown unnötiger Overhead).

**Frontend**:

- `PersonService` bekommt `listParents(familyId: string): Observable<PersonSummaryDTO[]>`.
- `CookingComponent.loadOrganisationData()` nutzt `listParents(familyId)` statt `list(familyId)` + Cast; `familyParents` wird typisiert als `PersonSummaryDTO[]`.
- `CookingDutyDialogComponent.getParentName()` liest `firstName`/`lastName` direkt vom Summary-Objekt statt über `basicProperties.find(...)`.

## B) Hover-Tooltip mit Schließtage-Art im Kochdienst-Datepicker

### Ausgangslage

`CookingDutyDialogComponent` nutzt aktuell den nativen Angular-Material `MatDatepicker` mit `dateFilter`, der geschlossene Tage nur deaktiviert (keine Tooltip-/Zellinhalt-Unterstützung). Ein wiederverwendbares Tooltip-Pattern existiert bereits in `ClosureCalendarComponent` (`tooltip(day)` + `matTooltip`, genutzt in der Eltern- und Admin-Schließtage-Ansicht) — diese Komponente ist aber für Mehrfach-Tage-Drag-Auswahl über einen ganzen Semesterzeitraum gebaut (Grid, kein Popup), nicht für Einzeltag-Auswahl in einem kompakten Feld.

### Lösung

`ClosureCalendarComponent` bekommt einen neuen Input `mode: 'range' | 'single' = 'range'`:

- **`'range'`** (Default): unverändertes bisheriges Verhalten (Drag-Auswahl, Ctrl-Toggle, Schließtage-Ansichten bleiben exakt wie heute).
- **`'single'`**: Klick auf einen `selectable` Tag ersetzt die Selektion durch genau diesen einen Tag (kein Drag, kein Ctrl-Toggle), `selectionChange` emittiert ein Array mit einem Element.

Das bestehende `tooltip(day)`/`matTooltip`-Verhalten wird unverändert wiederverwendet — kein neuer Tooltip-Mechanismus nötig. Bereits geschlossene Tage bleiben über die bestehende `selectable`/`dayBackground`-Logik optisch markiert und nicht auswählbar; das vorherige "gesperrter Tag"-Verhalten des `dateFilter` bleibt also erhalten, ergänzt um den Hover-Tooltip mit der Schließtage-Art.

`CookingDutyDialogComponent` ersetzt das `MatDatepicker`-Feld durch ein inline eingebettetes `<app-closure-calendar mode="single">`, mit `from`/`to` auf den benötigten Sichtbarkeitszeitraum (analog zur bisherigen `closedDates`-Berechnung in `cooking.component.ts`). Die serverseitige Absicherung über `ClosureGuard` bleibt unverändert die maßgebliche Prüfinstanz.

**Layout-Auswirkung**: Das Datumsfeld wird von einem Popup-Trigger zu einem inline dargestellten Kalender-Grid — der Dialog wird dadurch etwas höher, ist aber konsistent mit den bestehenden Schließtage-Ansichten der App.

## Testing

- Backend: Unit-/Integrationstest für `GET /persons/parents` (Filterung auf PARENT, korrekte Namen, leere Familie).
- Frontend: Test, dass `familyParents` nach dem Laden befüllte Namen zeigt (Regressionstest für den Type-Mismatch-Bug).
- Frontend: Test für `ClosureCalendarComponent` im `mode: 'single'` — Klick ersetzt Selektion statt sie zu erweitern, `selectionChange` liefert genau ein Element.
- Frontend: Test, dass im Kochdienst-Dialog ein geschlossener Tag nicht selektierbar ist und der Tooltip die Schließtage-Art anzeigt.
- Manueller Smoke-Test: Kochdienst als Elternteil anlegen, Dropdown zeigt Familienmitglieder, Tooltip über Schließtagen zeigt Art, Eintrag erscheint im Kalender.

## Out of Scope

- Gruppen-Multi-Selektion, Kalender-Auto-Refresh, serverseitige Schließzeiten-Sperre: bereits vorhanden, keine Änderung nötig.
- Reaktive/Live-Updates des Kalenders bei Änderungen durch andere Nutzer (Websocket/Polling) — nicht angefragt, würde eine separate Diskussion erfordern.
