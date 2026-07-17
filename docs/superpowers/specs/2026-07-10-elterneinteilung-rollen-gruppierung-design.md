# Elterneinteilung — Visuelle Gruppierung der Rollen nach Team

**Datum:** 2026-07-10

## Überblick

Erweiterung des bestehenden Rollen-Features ([2026-07-01-elterneinteilung-rollen-design.md](2026-07-01-elterneinteilung-rollen-design.md)): Statt einer flachen, ungruppierten Rollen-Spalte im Zuteilungsscreen soll die Rollenzuweisung visuell dem jeweiligen Team zugeordnet werden. Teams bekommen eine Farbe; wird eine Person einem Team zugewiesen, erscheint darunter eine Sektion mit den für dieses Team wählbaren Rollen. Zugewiesene Rollen werden in der Rollen-Spalte in der Farbe ihres Teams angezeigt.

Die bestehende Team-Zuweisungs-Logik (Toggle, Confirm-Dialog beim Abwählen eines Teams mit zugewiesenen Rollen) bleibt unverändert.

## Datenmodell

### Team-Farbe

`parent-team` FieldInstance-Value erweitert von `{label}` auf `{label, color}` — analog zu Gruppen (`{label, color}`).

Die `jsonSchema` der `parent-team`-FieldDefinition wird um `color: {type: 'string'}` ergänzt:

```json
{
  "type": "object",
  "properties": {
    "label": { "type": "string" },
    "color": { "type": "string" }
  }
}
```

Kein Backend-Modelländerung nötig (generisches `FieldInstance.value`-JSON). Bestehende Teams ohne `color` (Altdaten) zeigen einen Fallback (`#9e9e9e`) — kein Edit-Mechanismus für Bestandsdaten vorgesehen (analog zu Gruppen: nur Anlegen/Löschen).

## UI / Screens

### A) Settings > Organisation — Tab "Elterneinteilung" (Konfiguration)

- `parentTeamsForm` bekommt ein zusätzliches `color`-Feld (Color-Picker, Default `#4285f4`), analog zum bestehenden Gruppen-Formular
- `addParentTeam()` sendet `{ label, color }` als FieldInstance-Value
- Team-Name in der `mat-expansion-panel-header` bekommt einen Farb-Swatch davor (wie bei Gruppen in der `color`-Spalte)
- Rollen-Sub-Tabelle je Team bleibt unverändert

### B) Administration > Elterneinteilung — Zuteilungsscreen

**Teams-Spalte:**
- Bestehende Chip-Reihe zum Zuweisen/Abwählen bleibt unverändert (inkl. Confirm-Dialog-Logik beim Abwählen eines Teams mit zugewiesenen Rollen)
- Darunter: für **jedes aktuell zugewiesene Team** der Person eine eigene, gestapelte Sektion:
  - Linker Rahmen/Header in Team-Farbe + Team-Name
  - Darin alle Rollen-Chips dieses Teams (`getRolesForTeam(team)`), klickbar zum Zuweisen/Entfernen (`toggleRole`, unverändert)
  - Min/Max-Disabled-Logik unverändert (`isRoleDisabled`, `getRoleTooltip`)
  - Keine Sektion, wenn das Team keine Rollen konfiguriert hat (leere Liste → Sektion entfällt)
- Ist der Person kein Team zugewiesen, erscheint keine Rollen-Sektion

**Rollen-Spalte:**
- Bleibt bestehen als Übersicht aller zugewiesenen Rollen der Person, weiterhin klickbar zum Entfernen (`toggleRole`, unverändert)
- Jeder Chip wird in der Farbe des zugehörigen Teams eingefärbt (Hintergrundfarbe = Team-Farbe via `getTeamColor(team)`)
- Chip-Textfarbe bleibt fix (kein automatischer Kontrast-Ausgleich, analog zu Gruppen-Farben aktuell)

## Logik-Änderungen (elterneinteilung.component.ts)

### Neue/geänderte Methoden

```typescript
getRolesForTeam(team: FieldInstanceDTO): FieldInstanceDTO[]
// ersetzt/ergänzt getVisibleRoles(person) — filtert roles nach team.id,
// analog zur bestehenden Methode in organisation.component.ts

getTeamColor(team: FieldInstanceDTO): string
// (team.value as Record<string, unknown>)?.['color'] as string ?? '#9e9e9e'

getTeamForRole(role: FieldInstanceDTO): FieldInstanceDTO | undefined
// this.teams.find(t => t.id === (role.value as Record<string, unknown>)?.['teamInstanceId'])
```

### Unverändert

- `toggleTeam`, `doToggleTeam`, `toggleRole`, `isAssigned`, `isRoleAssigned`, `isRoleDisabled`, `getRoleTooltip`, `getAssignedCount`, `applyFilter`
- Backend-Endpunkte (`assignTeam`, `assignRole`) — keine Änderung

## Komponenten-Struktur

### Geänderte Dateien

```
frontend/src/app/
  settings/organisation/
    organisation.component.ts       ← color-Feld im parentTeamsForm, addParentTeam() sendet color
    organisation.component.html     ← Color-Picker im Team-Formular, Farb-Swatch im Panel-Header
  administration/elterneinteilung/
    elterneinteilung.component.ts   ← getRolesForTeam(), getTeamColor(), getTeamForRole()
    elterneinteilung.component.html ← Teams-Zelle: Rollen-Sektion pro zugewiesenem Team; Rollen-Chips farbig
    elterneinteilung.component.scss ← Styles für Team-Sektion (linker Rahmen in Team-Farbe) + farbige Rollen-Chips
```

### Keine Änderungen nötig an

- Backend (Entities, DTOs, Resources, Services) — reine Frontend-/Datenwert-Erweiterung
- `FieldInstanceService`, `PersonService` — bestehende Endpunkte reichen

## Sicherheit / Randfälle

- Bestehende Teams ohne `color` zeigen Fallback-Grau, kein Fehlerzustand
- Rollen ohne auffindbares Team (`teamInstanceId` zeigt auf gelöschtes Team) werden weiterhin gefiltert/ausgeblendet (bestehendes Verhalten aus dem 07-01-Design)
- Kein Backend-Enforcement der Min/Max-Grenzen (bestehendes akzeptiertes Risiko aus dem 07-01-Design, unverändert)
