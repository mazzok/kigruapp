# Elterneinteilung — Teams & Rollen: Verfeinerung der Anzeige Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Im Zuteilungsscreen (Administration > Elterneinteilung) entfällt die separate "Rollen"-Spalte, die "Teams"-Spalte wird zu "Teams & Rollen", das redundante Team-Namens-Label über den Rollen-Chips verschwindet, leere Team-Sektionen ohne konfigurierte Rollen werden unterdrückt, und Rollen-Chips werden optisch kompakter/outlined und in Team-Farbe dargestellt statt generisch blau.

**Architecture:** Reine Frontend-Änderung an `ElterneinteilungComponent` (Template, Styles, minimale TS-Aufräumarbeit). Keine neuen Methoden — alle benötigten Helper (`getAssignedTeams`, `getRolesForTeam`, `getTeamColor`) existieren bereits aus dem vorherigen Feature. Zwei Methoden (`getVisibleRoles`, `getTeamForRole`) werden mit der Rollen-Spalte ungenutzt und entfernt.

**Tech Stack:** Angular 18 (standalone components), Angular Material MDC-Chips (`mat-chip`, `mat-chip-set`), SCSS.

## Global Constraints

- `strictTemplates: true` ist in `tsconfig.json` aktiv — jede Template-Änderung muss mit `npm --prefix frontend run build` fehlerfrei durchlaufen.
- **CSS-Spezifitäts-Falle:** `.chip-assigned` setzt `background-color`/`color` mit `!important` (elterneinteilung.component.scss:21-25). Eine `!important`-Regel in einem Stylesheet schlägt eine Inline-`[style.*]`-Bindung ohne `!important`. Rollen-Chips dürfen die Klasse `.chip-assigned` daher **nicht** verwenden, sonst wird die dynamische Team-Farbe (via `[style.background-color]`) von der fixen Farbe überschrieben und bleibt unsichtbar (genau dieser Bug bestand vermutlich in der bisherigen, jetzt entfallenden Rollen-Spalte).
- Bestehende Team-Zuweisungs-Logik (`toggleTeam`, `doToggleTeam`, Confirm-Dialog beim Abwählen), Rollen-Zuweisung (`toggleRole`), Min/Max-Disabled-Logik (`isRoleDisabled`, `getRoleTooltip`) bleiben unverändert — keine dieser Methoden wird angefasst.
- Keine Backend-Änderungen.

---

### Task 1: Rollen-Spalte entfernen, Header umbenennen, Rollen-Chips team-farbig & kompakt

**Files:**
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts:47` (`displayedColumns`)
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html:32-77` (Teams- und Rollen-Spalte)
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss:37-41` (`.team-role-header` entfernen, `.role-chip` ergänzen)

**Interfaces:**
- Consumes (alle bestehend, unverändert): `teams: FieldInstanceDTO[]`, `isAssigned(person, team): boolean`, `toggleTeam(row, team): void`, `getTeamLabel(team): string`, `getAssignedTeams(person): FieldInstanceDTO[]`, `getRolesForTeam(team): FieldInstanceDTO[]`, `getTeamColor(team): string`, `isRoleAssigned(person, role): boolean`, `isRoleDisabled(person, role): boolean`, `getRoleTooltip(person, role): string`, `getRoleLabel(role): string`, `toggleRole(row, role): void`
- Produces: `displayedColumns = ['name', 'teams']` (kein `'rollen'` mehr) — Task 2 baut darauf auf, dass `getVisibleRoles`/`getTeamForRole` danach ungenutzt sind.

- [ ] **Step 1: `rollen` aus `displayedColumns` entfernen**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts`, Zeile 47, ersetzen:

```typescript
  displayedColumns = ['name', 'teams'];
```

- [ ] **Step 2: Teams-Spalte im Template neu aufbauen, Rollen-Spalte entfernen**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html`, die Zeilen 32-77 (kompletter `teams`-`ng-container` bis Ende des `rollen`-`ng-container`) ersetzen durch:

```html
      <ng-container matColumnDef="teams">
        <th mat-header-cell *matHeaderCellDef>Teams & Rollen</th>
        <td mat-cell *matCellDef="let row">
          <mat-chip-set>
            <mat-chip
              *ngFor="let team of teams"
              [class.chip-assigned]="isAssigned(row.person, team)"
              (click)="toggleTeam(row, team)">
              {{ getTeamLabel(team) }}
            </mat-chip>
          </mat-chip-set>

          <ng-container *ngFor="let team of getAssignedTeams(row.person)">
            <div class="team-role-section"
              *ngIf="getRolesForTeam(team).length > 0"
              [style.border-left-color]="getTeamColor(team)">
              <mat-chip-set>
                <mat-chip
                  *ngFor="let role of getRolesForTeam(team)"
                  class="role-chip"
                  [style.background-color]="isRoleAssigned(row.person, role) ? getTeamColor(team) : 'transparent'"
                  [style.border-color]="getTeamColor(team)"
                  [style.color]="isRoleAssigned(row.person, role) ? '#fff' : getTeamColor(team)"
                  [disabled]="isRoleDisabled(row.person, role)"
                  [matTooltip]="getRoleTooltip(row.person, role)"
                  (click)="toggleRole(row, role)">
                  {{ getRoleLabel(role) }}
                </mat-chip>
              </mat-chip-set>
            </div>
          </ng-container>
        </td>
      </ng-container>
```

Wichtig: `*ngFor` und `*ngIf` dürfen in Angular nicht auf demselben Element stehen — deshalb trägt der äußere `ng-container` das `*ngFor` über die zugewiesenen Teams, und das innere `div.team-role-section` das `*ngIf`, das die gesamte Sektion (inkl. Rahmen) unterdrückt, wenn das Team keine Rollen konfiguriert hat. Der frühere `team-role-header`-Div (Team-Name als Text) entfällt komplett — die Zuordnung zeigt sich jetzt ausschließlich über `border-left-color` der Sektion und über die Rollen-Chip-Farbe selbst.

- [ ] **Step 3: `.team-role-header`-Stil entfernen, `.role-chip`-Stil ergänzen**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss`, die Zeilen 37-41 (`.team-role-header { ... }`) ersatzlos löschen und stattdessen ans Ende der Datei anfügen:

```scss
.role-chip {
  --mdc-chip-container-height: 24px;
  font-size: 11px;
  border: 1px solid transparent;
}
```

Die Datei sieht danach (Ausschnitt ab `.team-role-section`) so aus:

```scss
.team-role-section {
  border-left: 3px solid;
  padding: 4px 0 4px 8px;
  margin: 8px 0;
}

.role-chip {
  --mdc-chip-container-height: 24px;
  font-size: 11px;
  border: 1px solid transparent;
}
```

- [ ] **Step 4: Strict-Template-Build verifizieren**

Run: `npm --prefix frontend run build`
Expected: `Application bundle generation complete` ohne Fehler. Ein Build-Fehler an dieser Stelle würde z. B. bedeuten, dass `displayedColumns` und die tatsächlich vorhandenen `matColumnDef`s nicht mehr übereinstimmen.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss
git commit -m "feat: remove Rollen column, nest role chips under team sections with team-colored styling"
```

---

### Task 2: Ungenutzte Helper-Methoden entfernen (`getVisibleRoles`, `getTeamForRole`)

**Files:**
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts:132-146`
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts:60-69`

**Interfaces:**
- Consumes: keine (reine Löschung)
- Produces: `ElterneinteilungComponent` ohne `getVisibleRoles()` und `getTeamForRole()`; beide wurden ausschließlich von der in Task 1 entfernten Rollen-Spalte und den zugehörigen Tests referenziert.

- [ ] **Step 1: Verifizieren, dass beide Methoden nirgends mehr referenziert werden**

Run: `grep -rn "getTeamForRole\|getVisibleRoles" frontend/src`
Expected: Nach Task 1 nur noch Treffer in `elterneinteilung.component.ts` (Definition) und `elterneinteilung.component.spec.ts` (Tests) — kein Treffer mehr in der `.html`.

- [ ] **Step 2: Zugehörige Tests aus der Spec-Datei entfernen**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts`, die beiden Tests für `getTeamForRole` (Zeilen 60-69) entfernen:

```typescript
  it('finds the team a role belongs to', () => {
    const gartenTeam = team('team-1', 'Garten', '#ff0000');
    component.teams = [gartenTeam];
    expect(component.getTeamForRole(role('role-1', 'Spielplatz', 'team-1'))).toBe(gartenTeam);
  });

  it('returns undefined when the role references a deleted team', () => {
    component.teams = [];
    expect(component.getTeamForRole(role('role-1', 'Spielplatz', 'team-deleted'))).toBeUndefined();
  });

```

Nach dem Entfernen folgt direkt der Test `'returns only the roles belonging to the given team'` auf den Test `'falls back to grey when no team is given'`.

- [ ] **Step 3: Tests laufen lassen — müssen weiterhin grün sein (Baseline vor der Löschung)**

Run: `npm --prefix frontend test -- --watch=false --include=**/elterneinteilung.component.spec.ts --browsers=ChromeHeadless`
Expected: PASS — 5 verbleibende Tests grün (die 2 `getTeamForRole`-Tests sind entfernt, die Methode selbst existiert im Component zu diesem Zeitpunkt noch, daher kein Bruch).

- [ ] **Step 4: `getVisibleRoles` und `getTeamForRole` aus dem Component entfernen**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts`, die Zeilen 132-137 (`getVisibleRoles`) und 143-146 (`getTeamForRole`) entfernen. Übrig bleibt an dieser Stelle (Ausschnitt):

```typescript
  isRoleAssigned(person: PersonDTO, role: FieldInstanceDTO): boolean {
    return (person.assignedRole ?? []).some((r) => r.id === role.id);
  }

  getTeamColor(team: FieldInstanceDTO | undefined): string {
    return (team?.value as Record<string, unknown>)?.['color'] as string ?? '#9e9e9e';
  }

  getRolesForTeam(team: FieldInstanceDTO): FieldInstanceDTO[] {
    return this.roles.filter(
      (r) => (r.value as Record<string, unknown>)?.['teamInstanceId'] === team.id
    );
  }

  getAssignedTeams(person: PersonDTO): FieldInstanceDTO[] {
    return this.teams.filter((t) => this.isAssigned(person, t));
  }
```

- [ ] **Step 5: Tests und Build erneut verifizieren**

Run: `npm --prefix frontend test -- --watch=false --include=**/elterneinteilung.component.spec.ts --browsers=ChromeHeadless`
Expected: PASS — 5 Tests grün.

Run: `npm --prefix frontend run build`
Expected: `Application bundle generation complete` ohne Fehler.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts
git commit -m "refactor: remove getVisibleRoles and getTeamForRole, unused after Rollen column removal"
```

---

### Task 3: Manueller Smoke-Test

**Files:** keine Code-Änderungen — Verifikation im Browser.

**Interfaces:**
- Consumes: alle Änderungen aus Task 1-2, laufendes Backend + Frontend (`start-backend-dev.bat`, `start-frontend-dev.bat`)

- [ ] **Step 1: Backend und Frontend starten**

Run: `start-backend-dev.bat` (Terminal 1) und `start-frontend-dev.bat` (Terminal 2), oder `npm --prefix frontend start` falls das Backend bereits läuft.

- [ ] **Step 2: Spalten-Header und fehlende Rollen-Spalte prüfen**

Im Browser: Administration > Elterneinteilung öffnen.
Erwartet: Es gibt nur noch die Spalten "Elternteil" und "Teams & Rollen" — keine separate "Rollen"-Spalte mehr.

- [ ] **Step 3: Team ohne Rollen zuweisen — keine leere Sektion**

Einer Person ein Team zuweisen, das keine Rollen konfiguriert hat (z. B. "Marketing" aus dem Screenshot).
Erwartet: Unterhalb der Team-Chips erscheint **keine** Sektion/Rahmen für dieses Team.

- [ ] **Step 4: Team mit Rollen zuweisen — Sektion ohne Text-Label, Rollen-Chips team-farbig**

Einer Person ein Team mit konfigurierten Rollen zuweisen (z. B. "Garten" mit Rolle "sandkiste").
Erwartet: Unterhalb der Team-Chips erscheint eine farbig umrandete Sektion **ohne** Team-Namens-Text darüber — nur die Rollen-Chips (kompakter, mit dünnem Rahmen in Team-Farbe, nicht ausgefüllt).
Eine Rolle anklicken weist sie zu.
Erwartet: Der angeklickte Rollen-Chip füllt sich mit der Team-Farbe (Hintergrund), Text wird weiß.

- [ ] **Step 5: Mehrere Teams mit Rollen — Unterscheidung ohne Label**

Derselben Person ein zweites Team mit anderer Farbe und eigenen Rollen zuweisen.
Erwartet: Zwei getrennte Sektionen erscheinen, jede mit eigener Randfarbe und eigenen team-farbigen Rollen-Chips — auch ohne Text-Label ist erkennbar, welche Rollen zu welchem Team gehören.

- [ ] **Step 6: Bestehende Logik unverändert prüfen**

Rolle einer zweiten Person im selben Team zuweisen, bis Max erreicht ist — Chip muss bei einer dritten Person ausgegraut/disabled mit Tooltip erscheinen.
Team bei einer Person mit zugewiesener Rolle abwählen — Confirm-Dialog muss erscheinen; bei Bestätigung müssen Team und Rolle entfernt werden (Rollen-Sektion verschwindet).

- [ ] **Step 7: Kein Commit nötig**

Dieser Task verändert keinen Code — nur Verifikation. Bei gefundenen Problemen: zurück zum jeweiligen Task, Fix committen.
