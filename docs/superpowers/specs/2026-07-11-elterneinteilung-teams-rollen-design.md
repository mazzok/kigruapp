# Elterneinteilung — Teams & Rollen: Verfeinerung der Anzeige

**Datum:** 2026-07-11

## Überblick

Follow-up zum gerade gemergten Feature [2026-07-10-elterneinteilung-rollen-gruppierung-design.md](2026-07-10-elterneinteilung-rollen-gruppierung-design.md). Reine visuelle Verfeinerung des Zuteilungsscreens (Administration > Elterneinteilung): redundante Beschriftung entfernen, Rollen klar als Sub-Element von Teams erkennbar machen, die separate Rollen-Spalte entfernen und leere Sektionen unterdrücken.

Keine Änderung an Team-Zuweisung, Rollen-Zuweisung, Min/Max-Logik oder Backend.

## Änderungen im Detail

### 1. Spalte "Rollen" entfällt, "Teams" → "Teams & Rollen"

- `displayedColumns` verliert `'rollen'`; die gesamte `matColumnDef="rollen"`-Spalte wird aus dem Template entfernt.
- Der Header der verbleibenden Teams-Spalte wird von "Teams" zu "Teams & Rollen" umbenannt.
- Damit werden `getVisibleRoles()` und `getTeamForRole()` in `elterneinteilung.component.ts` ungenutzt und werden entfernt.

### 2. Kein redundantes Team-Label über den Rollen-Chips

- Der `team-role-header`-Div (zeigt aktuell den Team-Namen z. B. "Garten" nochmal über den Rollen-Chips) entfällt vollständig.
- Die Zuordnung Rolle → Team wird stattdessen **direkt am Rollen-Chip** sichtbar gemacht (siehe Punkt 3), nicht mehr über ein Text-Label. Das löst auch den Mehrfach-Team-Fall: Ist eine Person mehreren Teams mit Rollen zugewiesen, sind die Chips jeder Sektion in der jeweiligen Team-Farbe erkennbar — kein Label nötig.
- Die farbige linke Randlinie (`team-role-section`, `border-left-color`) bleibt zusätzlich als Sektions-Trenner erhalten.

### 3. Rollen-Chips visuell abgesetzt von Team-Chips (Teams = Superstruktur, Rollen = Subelement)

Team-Chips bleiben wie bisher (volle Pill, generisches Blau bei Zuweisung via `.chip-assigned`).

Rollen-Chips bekommen eine neue Klasse `.role-chip` und verhalten sich abweichend:
- **Kompakter:** kleinere Schriftgröße/Chip-Höhe als Team-Chips.
- **Outline statt Fill, in Team-Farbe:** nicht zugewiesene Rollen zeigen einen dünnen Rahmen in der Farbe des zugehörigen Teams (`getTeamColor(team)`), transparenter Hintergrund, Text in Team-Farbe.
- **Zugewiesene Rollen:** gefüllter Hintergrund in Team-Farbe (`getTeamColor(team)`) statt des generischen `.chip-assigned`-Blaus — das bisherige Verhalten aus der (jetzt entfallenden) Rollen-Spalte wandert so in die Teams-Spalte.
- Damit ist die Team-Zugehörigkeit einer Rolle allein am Chip selbst ablesbar, unabhängig von Sektions-Label oder Randfarbe.

`getTeamColor()` bleibt unverändert (bereits vorhanden), wird nur zusätzlich in der Teams-Spalte statt nur in der (entfallenden) Rollen-Spalte verwendet.

### 4. Keine Sektion für Teams ohne konfigurierte Rollen

- Aktuell wird `team-role-section` für **jedes zugewiesene Team** gerendert, auch wenn `getRolesForTeam(team)` leer ist (nur die innere Chip-Liste ist dann leer, der Rahmen/das leere Feld bleibt sichtbar).
- Der `*ngFor` über `getAssignedTeams(row.person)` wird um einen Filter ergänzt, sodass Teams ohne konfigurierte Rollen gar keine Sektion mehr erzeugen. Am einfachsten: `getAssignedTeams(person)` liefert wie bisher alle zugewiesenen Teams (wird weiterhin für andere Zwecke nicht gebraucht, aber Signatur bleibt gleich), im Template wird zusätzlich `*ngIf="getRolesForTeam(team).length > 0"` auf die gesamte `team-role-section` (nicht nur auf das `mat-chip-set` darin) angewendet.

## Komponenten-Struktur

### Geänderte Dateien

```
frontend/src/app/administration/elterneinteilung/
  elterneinteilung.component.html   ← Rollen-Spalte entfernt, Header umbenannt, team-role-header entfernt,
                                        *ngIf auf team-role-section, .role-chip mit dynamischer Team-Farbe
  elterneinteilung.component.ts     ← getVisibleRoles(), getTeamForRole() entfernt (unused)
  elterneinteilung.component.scss   ← .team-role-header-Regel entfernt, neue .role-chip-Styles (kompakt, outline)
```

### Unverändert

- `toggleTeam`, `doToggleTeam`, `toggleRole`, `isAssigned`, `isRoleAssigned`, `isRoleDisabled`, `getRoleTooltip`, `getAssignedCount`, `applyFilter`, `getTeamColor`, `getRolesForTeam`, `getAssignedTeams`
- Backend, Settings/Organisation-Screen — keine Änderung

## Randfälle

- Person ohne zugewiesenes Team: keine Sektion (bestehendes Verhalten, unverändert).
- Team ohne konfigurierte Rollen: keine Sektion (neu, siehe Punkt 4).
- Rolle ohne auffindbares Team (gelöschtes Team): bereits bestehende Filterung aus dem 07-01-Design bleibt unverändert — solche Rollen tauchen in keiner Sektion auf.
- Teams ohne `color` (Altdaten): Fallback-Grau via `getTeamColor()`, unverändert.
