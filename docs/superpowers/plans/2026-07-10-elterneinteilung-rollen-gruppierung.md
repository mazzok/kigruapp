# Elterneinteilung — Visuelle Gruppierung der Rollen nach Team Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teams bekommen eine Farbe; im Zuteilungsscreen erscheint pro zugewiesenem Team eine eigene Rollen-Sektion darunter, und zugewiesene Rollen werden in der Rollen-Spalte in der Farbe ihres Teams angezeigt.

**Architecture:** Reine Frontend-Änderung. `parent-team` FieldInstance-Value wird von `{label}` auf `{label, color}` erweitert (analog zu Gruppen). Der Konfigurationsscreen (`OrganisationComponent`) bekommt einen Color-Picker beim Team-Anlegen. Der Zuteilungsscreen (`ElterneinteilungComponent`) gruppiert die Rollen-Chips pro zugewiesenem Team und färbt Chips in der Team-Farbe ein.

**Tech Stack:** Angular 17 (standalone components), Angular Material (mat-table, mat-chip, mat-expansion), RxJS.

## Global Constraints

- Kein Backend-Änderungen nötig — `FieldInstance.value` ist generisches JSON, keine Schema-Migration auf DB-Ebene erforderlich.
- Bestehende Teams ohne `color` (Altdaten) zeigen Fallback-Farbe `#9e9e9e` — kein Edit-Mechanismus für Bestandsdaten (Muster wie bei Gruppen: nur Anlegen/Löschen).
- `strictTemplates: true` ist in `tsconfig.json` aktiv — jede Template-Änderung muss mit `npm run build` fehlerfrei durchlaufen.
- Bestehende Team-Zuweisungs-Logik (`toggleTeam`, `doToggleTeam`, Confirm-Dialog beim Abwählen) bleibt unverändert.

---

### Task 1: Team-Farbe im Konfigurationsscreen — Formular & Datenwert (organisation.component.ts)

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts:48-50` (parentTeamsForm)
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts:229-259` (addParentTeam)
- Test: `frontend/src/app/settings/organisation/organisation.component.spec.ts` (neu)

**Interfaces:**
- Consumes: `OrganisationService.getByTag/update`, `FieldDefinitionService.create`, `FieldInstanceService.create/listByDefinitionId` (unverändert, bestehende Signaturen)
- Produces: `parentTeamsForm` mit Feldern `{ labelDe: string, color: string }`; `addParentTeam()` sendet FieldInstance-Value `{ label, color }`; `parent-team` FieldDefinition-jsonSchema enthält `color: { type: 'string' }`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/settings/organisation/organisation.component.spec.ts`:

```typescript
import { of } from 'rxjs';
import { OrganisationComponent } from './organisation.component';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { MatDialog } from '@angular/material/dialog';
import { OrganisationDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

class FakeOrganisationService {
  updateCalls: { id: string; body: unknown }[] = [];
  orgsByTag: Record<string, OrganisationDTO> = {
    groups: { id: 'org-groups', tag: 'groups', definitions: [], entries: [] },
    'duty-settings': { id: 'org-duty', tag: 'duty-settings', definitions: [], entries: [] },
    'parent-teams': { id: 'org-teams', tag: 'parent-teams', definitions: [], entries: [] },
    'parent-team-roles': { id: 'org-roles', tag: 'parent-team-roles', definitions: [], entries: [] },
  };
  getByTag(tag: string) {
    return of(this.orgsByTag[tag]);
  }
  update(id: string, body: unknown) {
    this.updateCalls.push({ id, body });
    return of(this.orgsByTag['parent-teams']);
  }
}

class FakeFieldDefinitionService {
  createCalls: FieldDefinition[] = [];
  create(def: FieldDefinition) {
    this.createCalls.push(def);
    return of({ ...def, id: 'def-team-new' });
  }
}

class FakeFieldInstanceService {
  createCalls: { definitionId: string; value: unknown }[] = [];
  create(definitionId: string, value: unknown) {
    this.createCalls.push({ definitionId, value });
    return of({ id: 'instance-1' });
  }
  listByDefinitionId(_definitionId: string) {
    return of([] as FieldInstanceDTO[]);
  }
  delete(_id: string) {
    return of(undefined);
  }
}

describe('OrganisationComponent - Team-Farbe', () => {
  let component: OrganisationComponent;
  let orgService: FakeOrganisationService;
  let fieldDefService: FakeFieldDefinitionService;
  let fieldInstanceService: FakeFieldInstanceService;

  beforeEach(() => {
    orgService = new FakeOrganisationService();
    fieldDefService = new FakeFieldDefinitionService();
    fieldInstanceService = new FakeFieldInstanceService();
    const fakeDialog = { open: () => ({ afterClosed: () => of(null) }) } as unknown as MatDialog;

    component = new OrganisationComponent(
      orgService as unknown as OrganisationService,
      fieldDefService as unknown as FieldDefinitionService,
      fieldInstanceService as unknown as FieldInstanceService,
      fakeDialog,
    );
  });

  it('sends label and color when creating the first parent-team FieldDefinition', () => {
    component.ngOnInit();
    component.parentTeamsForm.setValue({ labelDe: 'Garten', color: '#ff0000' });

    component.addParentTeam();

    expect(fieldDefService.createCalls.length).toBe(1);
    const jsonSchema = fieldDefService.createCalls[0].jsonSchema as { properties: Record<string, unknown> };
    expect(jsonSchema.properties['color']).toEqual({ type: 'string' });
    expect(fieldInstanceService.createCalls[0].value).toEqual({ label: 'Garten', color: '#ff0000' });
  });

  it('sends label and color when adding a team to an existing definition', () => {
    orgService.orgsByTag['parent-teams'] = {
      id: 'org-teams',
      tag: 'parent-teams',
      definitions: [{
        id: 'def-team-1', fieldName: 'parent-team',
        label: { de: 'Elterneinteilung' }, jsonSchema: {}, required: false,
      }],
      entries: [],
    };
    component.ngOnInit();
    component.parentTeamsForm.setValue({ labelDe: 'Kueche', color: '#00ff00' });

    component.addParentTeam();

    expect(fieldInstanceService.createCalls[0]).toEqual({
      definitionId: 'def-team-1',
      value: { label: 'Kueche', color: '#00ff00' },
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend test -- --watch=false --include=**/organisation.component.spec.ts`
Expected: FAIL — `parentTeamsForm.setValue` throws or TypeScript error, because `parentTeamsForm` currently has no `color` control (property does not exist / value shape mismatch).

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/app/settings/organisation/organisation.component.ts`, replace the `parentTeamsForm` declaration (lines 48-50):

```typescript
  parentTeamsForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    color: new FormControl('#4285f4', Validators.required),
  });
```

Replace `addParentTeam()` (lines 229-259):

```typescript
  addParentTeam(): void {
    if (!this.parentTeamsForm.valid || !this.parentTeamsOrg) return;
    const labelDe = this.parentTeamsForm.value.labelDe!;
    const color = this.parentTeamsForm.value.color!;
    const value = { label: labelDe, color };

    if (this.parentTeamsDefinitionId) {
      this.fieldInstanceService.create(this.parentTeamsDefinitionId, value).subscribe(() => {
        this.parentTeamsForm.reset({ color: '#4285f4' });
        this.loadParentTeams();
      });
    } else {
      const templateDef: FieldDefinition = {
        fieldName: 'parent-team',
        label: { de: 'Elterneinteilung' },
        jsonSchema: { type: 'object', properties: { label: { type: 'string' }, color: { type: 'string' } } },
        required: false,
      };
      this.fieldDefService.create(templateDef).pipe(
        switchMap((created) => {
          this.parentTeamsDefinitionId = created.id!;
          const updatedIds = [...this.parentTeamsOrg!.definitions.map((d) => d.id!), created.id!];
          return this.orgService.update(this.parentTeamsOrg!.id, { definitionIds: updatedIds }).pipe(
            switchMap(() => this.fieldInstanceService.create(created.id!, value))
          );
        })
      ).subscribe(() => {
        this.parentTeamsForm.reset({ color: '#4285f4' });
        this.loadParentTeams();
      });
    }
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix frontend test -- --watch=false --include=**/organisation.component.spec.ts`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.ts frontend/src/app/settings/organisation/organisation.component.spec.ts
git commit -m "feat: add color field to parent-team FieldDefinition and creation form"
```

---

### Task 2: Team-Farbe im Konfigurationsscreen — Template (organisation.component.html)

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:103-113` (Team-Formular)
- Modify: `frontend/src/app/settings/organisation/organisation.component.html:121-122` (Panel-Header)

**Interfaces:**
- Consumes: `parentTeamsForm` (mit `color`-Control aus Task 1), `team.value` (`{label, color}`)
- Produces: sichtbarer Color-Picker beim Team-Anlegen; Farb-Swatch im Panel-Header pro Team

- [ ] **Step 1: Add color picker to the team creation form**

Replace lines 103-113 in `frontend/src/app/settings/organisation/organisation.component.html`:

```html
        <form [formGroup]="parentTeamsForm" (ngSubmit)="addParentTeam()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Teamname</mat-label>
              <input matInput formControlName="labelDe">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Farbe</mat-label>
              <input matInput formControlName="color" type="color">
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" [disabled]="!parentTeamsForm.valid">
              Team hinzufuegen
            </button>
          </div>
        </form>
```

- [ ] **Step 2: Add a color swatch to the panel header**

Replace lines 121-122:

```html
            <mat-panel-title>
              <span class="color-swatch" [style.background-color]="$any(team.value).color ?? '#9e9e9e'"></span>
              {{ $any(team.value).label }}
            </mat-panel-title>
```

- [ ] **Step 3: Verify the template compiles under strict template checking**

Run: `npm --prefix frontend run build`
Expected: `Application bundle generation complete` with no errors (strictTemplates is enabled, so a binding mismatch would fail this build).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.html
git commit -m "feat: show color picker and swatch for parent teams in Organisation settings"
```

---

### Task 3: Rollen nach Team gruppieren — Logik (elterneinteilung.component.ts)

**Files:**
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts:132-137` (nach `getVisibleRoles`)
- Test: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts` (neu)

**Interfaces:**
- Consumes: `this.teams: FieldInstanceDTO[]`, `this.roles: FieldInstanceDTO[]`, `isAssigned(person, team): boolean` (bestehend)
- Produces:
  - `getTeamColor(team: FieldInstanceDTO | undefined): string`
  - `getTeamForRole(role: FieldInstanceDTO): FieldInstanceDTO | undefined`
  - `getRolesForTeam(team: FieldInstanceDTO): FieldInstanceDTO[]`
  - `getAssignedTeams(person: PersonDTO): FieldInstanceDTO[]`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts`:

```typescript
import { ElterneinteilungComponent } from './elterneinteilung.component';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { MatDialog } from '@angular/material/dialog';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { PersonDTO } from '../../shared/models/person.model';

function team(id: string, label: string, color?: string): FieldInstanceDTO {
  return {
    id, definitionId: 'def-team', fieldName: 'parent-team',
    label: { de: 'Elterneinteilung' }, jsonSchema: {}, required: false,
    value: color ? { label, color } : { label },
    definitionOutdated: false,
  };
}

function role(id: string, label: string, teamInstanceId: string): FieldInstanceDTO {
  return {
    id, definitionId: 'def-role', fieldName: 'parent-team-role',
    label: { de: 'Rolle' }, jsonSchema: {}, required: false,
    value: { label, teamInstanceId },
    definitionOutdated: false,
  };
}

function person(assignedDuty: FieldInstanceDTO[] = []): PersonDTO {
  return {
    id: 'p1', familyId: 'f1',
    basicProperties: [], roles: [], schedules: [], duties: [], finance: [],
    customProperties: [], organisationalUnit: [],
    assignedDuty, assignedRole: [],
  };
}

describe('ElterneinteilungComponent - Team-Farbe & Gruppierung', () => {
  let component: ElterneinteilungComponent;

  beforeEach(() => {
    component = new ElterneinteilungComponent(
      {} as PersonService,
      {} as OrganisationService,
      {} as FieldInstanceService,
      {} as MatDialog,
    );
  });

  it('returns the team color when set', () => {
    expect(component.getTeamColor(team('team-1', 'Garten', '#ff0000'))).toBe('#ff0000');
  });

  it('falls back to grey when the team has no color (legacy data)', () => {
    expect(component.getTeamColor(team('team-1', 'Garten'))).toBe('#9e9e9e');
  });

  it('falls back to grey when no team is given', () => {
    expect(component.getTeamColor(undefined)).toBe('#9e9e9e');
  });

  it('finds the team a role belongs to', () => {
    const gartenTeam = team('team-1', 'Garten', '#ff0000');
    component.teams = [gartenTeam];
    expect(component.getTeamForRole(role('role-1', 'Spielplatz', 'team-1'))).toBe(gartenTeam);
  });

  it('returns undefined when the role references a deleted team', () => {
    component.teams = [];
    expect(component.getTeamForRole(role('role-1', 'Spielplatz', 'team-deleted'))).toBeUndefined();
  });

  it('returns only the roles belonging to the given team', () => {
    const spielplatzRole = role('role-1', 'Spielplatz', 'team-1');
    const kuecheRole = role('role-2', 'Kueche', 'team-2');
    component.roles = [spielplatzRole, kuecheRole];
    expect(component.getRolesForTeam(team('team-1', 'Garten'))).toEqual([spielplatzRole]);
  });

  it('returns the currently assigned teams for a person', () => {
    const gartenTeam = team('team-1', 'Garten', '#ff0000');
    const kuecheTeam = team('team-2', 'Kueche', '#00ff00');
    component.teams = [gartenTeam, kuecheTeam];
    const p = person([gartenTeam]);
    expect(component.getAssignedTeams(p)).toEqual([gartenTeam]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm --prefix frontend test -- --watch=false --include=**/elterneinteilung.component.spec.ts`
Expected: FAIL with TypeScript errors — `getTeamColor`, `getTeamForRole`, `getRolesForTeam`, `getAssignedTeams` do not exist on `ElterneinteilungComponent`.

- [ ] **Step 3: Write minimal implementation**

In `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts`, insert after the existing `getVisibleRoles` method (after line 137, before `getAssignedCount`):

```typescript
  getTeamColor(team: FieldInstanceDTO | undefined): string {
    return (team?.value as Record<string, unknown>)?.['color'] as string ?? '#9e9e9e';
  }

  getTeamForRole(role: FieldInstanceDTO): FieldInstanceDTO | undefined {
    const teamId = (role.value as Record<string, unknown>)?.['teamInstanceId'] as string;
    return this.teams.find((t) => t.id === teamId);
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

- [ ] **Step 4: Run test to verify it passes**

Run: `npm --prefix frontend test -- --watch=false --include=**/elterneinteilung.component.spec.ts`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts frontend/src/app/administration/elterneinteilung/elterneinteilung.component.spec.ts
git commit -m "feat: add team-color and per-team role lookup helpers to ElterneinteilungComponent"
```

---

### Task 4: Rollen nach Team gruppieren — Template & Styles (elterneinteilung.component.html/.scss)

**Files:**
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html:32-60` (Teams- und Rollen-Spalte)
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss` (neue Klassen anhängen)

**Interfaces:**
- Consumes: `getAssignedTeams`, `getRolesForTeam`, `getTeamColor`, `getTeamForRole` (aus Task 3); bestehend: `teams`, `isAssigned`, `toggleTeam`, `isRoleAssigned`, `isRoleDisabled`, `getRoleTooltip`, `toggleRole`, `getVisibleRoles`, `getTeamLabel`, `getRoleLabel`

- [ ] **Step 1: Group role chips under each assigned team in the Teams column**

Replace the `teams` column (lines 32-44) in `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html`:

```html
      <ng-container matColumnDef="teams">
        <th mat-header-cell *matHeaderCellDef>Teams</th>
        <td mat-cell *matCellDef="let row">
          <mat-chip-set>
            <mat-chip
              *ngFor="let team of teams"
              [class.chip-assigned]="isAssigned(row.person, team)"
              (click)="toggleTeam(row, team)">
              {{ getTeamLabel(team) }}
            </mat-chip>
          </mat-chip-set>

          <div class="team-role-section"
            *ngFor="let team of getAssignedTeams(row.person)"
            [style.border-left-color]="getTeamColor(team)">
            <div class="team-role-header" [style.color]="getTeamColor(team)">{{ getTeamLabel(team) }}</div>
            <mat-chip-set *ngIf="getRolesForTeam(team).length > 0">
              <mat-chip
                *ngFor="let role of getRolesForTeam(team)"
                [class.chip-assigned]="isRoleAssigned(row.person, role)"
                [disabled]="isRoleDisabled(row.person, role)"
                [matTooltip]="getRoleTooltip(row.person, role)"
                (click)="toggleRole(row, role)">
                {{ getRoleLabel(role) }}
              </mat-chip>
            </mat-chip-set>
          </div>
        </td>
      </ng-container>
```

- [ ] **Step 2: Color the Rollen column chips by their team**

Replace the `rollen` column (lines 46-60):

```html
      <ng-container matColumnDef="rollen">
        <th mat-header-cell *matHeaderCellDef>Rollen</th>
        <td mat-cell *matCellDef="let row">
          <mat-chip-set>
            <mat-chip
              *ngFor="let role of getVisibleRoles(row.person)"
              [class.chip-assigned]="isRoleAssigned(row.person, role)"
              [style.background-color]="isRoleAssigned(row.person, role) ? getTeamColor(getTeamForRole(role)) : null"
              [disabled]="isRoleDisabled(row.person, role)"
              [matTooltip]="getRoleTooltip(row.person, role)"
              (click)="toggleRole(row, role)">
              {{ getRoleLabel(role) }}
            </mat-chip>
          </mat-chip-set>
        </td>
      </ng-container>
```

- [ ] **Step 3: Add styles for the per-team role section**

Append to `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss`:

```scss
.team-role-section {
  border-left: 3px solid;
  padding: 4px 0 4px 8px;
  margin: 8px 0;
}

.team-role-header {
  font-size: 12px;
  font-weight: 500;
  margin-bottom: 4px;
}
```

- [ ] **Step 4: Verify the template compiles under strict template checking**

Run: `npm --prefix frontend run build`
Expected: `Application bundle generation complete` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss
git commit -m "feat: group role chips under each assigned team and color them by team"
```

---

### Task 5: Manueller Smoke-Test

**Files:** keine Code-Änderungen — Verifikation der gesamten Feature-Kette im Browser.

**Interfaces:**
- Consumes: alle Änderungen aus Task 1-4, laufendes Backend + Frontend (`start-backend-dev.bat`, `start-frontend-dev.bat`)

- [ ] **Step 1: Start backend and frontend**

Run: `start-backend-dev.bat` (in einem Terminal) und `start-frontend-dev.bat` (in einem zweiten Terminal), oder `npm --prefix frontend start` falls das Backend bereits läuft.

- [ ] **Step 2: Configure a team with a color**

Im Browser: Einstellungen > Organisation > Tab "Elterneinteilung" öffnen, ein Team mit Farbe (z.B. Rot) anlegen, mind. eine Rolle mit Max=1 für dieses Team anlegen.
Erwartet: Team-Panel-Header zeigt einen roten Farb-Swatch vor dem Team-Namen.

- [ ] **Step 3: Assign a parent to the team and grant a role**

Im Browser: Administration > Elterneinteilung öffnen, das konfigurierte Team einer Person zuweisen.
Erwartet: Unterhalb der Team-Chips erscheint eine rot umrandete Sektion mit dem Team-Namen (rot) und den Rollen-Chips dieses Teams. Rolle anklicken weist sie zu.
Erwartet: In der Rollen-Spalte erscheint der Rollen-Chip mit rotem Hintergrund.

- [ ] **Step 4: Verify max-limit and team-deselect behavior are unchanged**

Rolle einer zweiten Person im selben Team zuweisen, bis Max erreicht ist — Chip muss bei einer dritten Person ausgegraut/disabled mit Tooltip erscheinen.
Team bei einer Person mit zugewiesener Rolle abwählen — Confirm-Dialog muss erscheinen; bei Bestätigung müssen Team und Rolle entfernt werden (Rollen-Sektion verschwindet, Rollen-Chip verschwindet aus der Rollen-Spalte).

- [ ] **Step 5: No commit needed**

Dieser Task verändert keinen Code — nur Verifikation. Bei gefundenen Problemen: zurück zum jeweiligen Task, Fix committen.
