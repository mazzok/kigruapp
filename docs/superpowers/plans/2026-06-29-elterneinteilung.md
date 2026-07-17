# Elterneinteilung — Teamzuweisung für Eltern Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eltern können einem oder mehreren konfigurierbaren Teams (Garten, Marketing, Finanzen, …) zugeordnet werden; Teams werden in `settings/organisation` verwaltet, Zuweisung erfolgt in `administration/elterneinteilung`.

**Architecture:** Teams sind `FieldInstance`-Objekte unter einem `Organisation`-Eintrag mit tag `'parent-teams'` (identisches Muster wie Gruppen für Kinder). Zuweisung wird als `assignedDuty: List<FieldRef>` auf der `Person`-Entity gespeichert. Ein neuer `PATCH /persons/{id}/assigned-duty`-Endpoint togglet einzelne Teamzuweisungen ohne bestehende Refs zu löschen.

**Tech Stack:** Quarkus (MongoDB/Panache), Angular 17 Standalone Components, Angular Material (Tabs, Table, Chips, Select), RxJS (switchMap, forkJoin, catchError)

## Global Constraints

- Alle Backend-Klassen im Package `at.kigruapp`
- Backend-Endpunkte unter `/api/v1/`
- Angular Standalone Components, kein NgModule
- `FieldInstance`-Objekte für Teams sind **geteilt** — niemals per `createFieldInstances()` neu anlegen, nur FieldRefs speichern
- `assignedDuty` wird beim `DELETE /persons/{id}` **nicht** mitgelöscht (shared instances)
- Admin-Guard für beide neuen Screens bereits durch bestehende Route-Struktur abgedeckt

---

### Task 1: Backend — `assignedDuty` auf Person-Entity + PATCH-Endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/Person.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`
- Test: `backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java`

**Interfaces:**
- Produces: `PATCH /api/v1/persons/{id}/assigned-duty` → body `{ definitionId, fieldInstanceId }` → toggle FieldRef in `person.assignedDuty` → 204 / 404
- Produces: `PersonDTO.assignedDuty: List<FieldInstanceDTO>` — resolved via `resolveRefs()`

- [ ] **Step 1: Neues Feld `assignedDuty` in `Person.java`**

Füge nach `organisationalUnit` hinzu:

```java
// in Person.java, nach:
public List<FieldRef> organisationalUnit = new ArrayList<>();

// ergänze:
public List<FieldRef> assignedDuty = new ArrayList<>();
```

- [ ] **Step 2: Neues Feld `assignedDuty` in `PersonDTO.java`**

```java
// in PersonDTO.java, nach:
public List<FieldInstanceDTO> organisationalUnit;

// ergänze:
public List<FieldInstanceDTO> assignedDuty;
```

- [ ] **Step 3: `toFullDTO()` in `PersonResource.java` erweitern**

In `toFullDTO()`, nach:
```java
dto.organisationalUnit = resolveRefs(person.organisationalUnit != null ? person.organisationalUnit : List.of());
```
ergänze:
```java
dto.assignedDuty = resolveRefs(person.assignedDuty != null ? person.assignedDuty : List.of());
```

- [ ] **Step 4: `TeamAssignmentRequest`-Record und PATCH-Endpoint hinzufügen**

In `PersonResource.java`, nach dem bestehenden `GroupAssignmentRequest`-Record:
```java
public record TeamAssignmentRequest(String definitionId, String fieldInstanceId) {}
```

Nach dem `patchGroup`-Endpoint (nach Zeile ~358) den neuen Endpoint einfügen:
```java
@PATCH
@Path("/{id}/assigned-duty")
public Response patchAssignedDuty(@PathParam("id") String id, TeamAssignmentRequest request) {
    Person person = Person.findById(new ObjectId(id));
    if (person == null) throw new NotFoundException();

    ObjectId defId = new ObjectId(request.definitionId());
    ObjectId instId = new ObjectId(request.fieldInstanceId());

    if (person.assignedDuty == null) {
        person.assignedDuty = new ArrayList<>();
    }

    boolean removed = person.assignedDuty.removeIf(ref -> ref.fieldInstanceId.equals(instId));
    if (!removed) {
        person.assignedDuty.add(new FieldRef(defId, instId));
    }

    person.updatedAt = Instant.now();
    person.update();
    return Response.noContent().build();
}
```

- [ ] **Step 5: Failing-Test schreiben**

In `PersonResourceTest.java` ergänzen:
```java
@Test
public void testPatchAssignedDutyNotFound() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"definitionId\": \"000000000000000000000000\", \"fieldInstanceId\": \"000000000000000000000000\"}")
        .when().patch("/api/v1/persons/000000000000000000000000/assigned-duty")
        .then()
        .statusCode(404);
}
```

- [ ] **Step 6: Tests ausführen**

```bash
cd backend && ./mvnw test -pl . -Dtest=PersonResourceTest -q
```

Erwartetes Ergebnis: alle Tests grün inkl. `testPatchAssignedDutyNotFound`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/at/kigruapp/entity/Person.java \
        backend/src/main/java/at/kigruapp/dto/PersonDTO.java \
        backend/src/main/java/at/kigruapp/resource/PersonResource.java \
        backend/src/test/java/at/kigruapp/resource/PersonResourceTest.java
git commit -m "feat: add assignedDuty section to Person with PATCH toggle endpoint"
```

---

### Task 2: Frontend — Person-Modell + Service

**Files:**
- Modify: `frontend/src/app/shared/models/person.model.ts`
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Produces: `PersonDTO.assignedDuty: FieldInstanceDTO[]`
- Produces: `PersonService.assignTeam(personId, definitionId, fieldInstanceId): Observable<void>`

- [ ] **Step 1: `assignedDuty` zu `Person`- und `PersonDTO`-Interfaces in `person.model.ts` hinzufügen**

In `person.model.ts`:

```typescript
// Person-Interface: nach organisationalUnit: FieldRef[];
assignedDuty: FieldRef[];

// PersonDTO-Interface: nach organisationalUnit: FieldInstanceDTO[];
assignedDuty: FieldInstanceDTO[];
```

Vollständige aktualisierte Interfaces:
```typescript
export interface Person {
  id?: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldRef[];
  roles: FieldRef[];
  schedules: FieldRef[];
  duties: FieldRef[];
  finance: FieldRef[];
  customProperties: FieldRef[];
  organisationalUnit: FieldRef[];
  assignedDuty: FieldRef[];
  createdAt?: string;
  updatedAt?: string;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldInstanceDTO[];
  roles: FieldInstanceDTO[];
  schedules: FieldInstanceDTO[];
  duties: FieldInstanceDTO[];
  finance: FieldInstanceDTO[];
  customProperties: FieldInstanceDTO[];
  organisationalUnit: FieldInstanceDTO[];
  assignedDuty: FieldInstanceDTO[];
  createdAt?: string;
  updatedAt?: string;
}
```

- [ ] **Step 2: `assignTeam()` zu `PersonService` hinzufügen**

In `person.service.ts`, nach der bestehenden `assignGroup()`-Methode:
```typescript
assignTeam(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
  return this.api.patch<void>(`/persons/${personId}/assigned-duty`, { definitionId, fieldInstanceId });
}
```

- [ ] **Step 3: Kompilierung prüfen**

```bash
cd frontend && npx ng build --configuration development 2>&1 | grep -E "error|warning" | head -20
```

Erwartetes Ergebnis: keine Typfehler.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/shared/models/person.model.ts \
        frontend/src/app/shared/services/person.service.ts
git commit -m "feat: add assignedDuty to Person model and assignTeam to PersonService"
```

---

### Task 3: Frontend — Neuer Tab "Elterneinteilung" in OrganisationComponent

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`

**Interfaces:**
- Consumes: `OrganisationService.getByTag('parent-teams')`, `FieldDefinitionService.create()`, `FieldInstanceService.create()`, `FieldInstanceService.listByDefinitionId()`, `FieldInstanceService.delete()`, `OrganisationService.update()`
- Produces: Tab "Elterneinteilung" mit Teamverwaltung (Hinzufügen / Löschen)

- [ ] **Step 1: Klassenvariablen für Parent-Teams-Tab in `organisation.component.ts` hinzufügen**

Nach den bestehenden "Duty settings tab"-Variablen (nach Zeile ~50):
```typescript
// Parent teams tab
parentTeamsOrg: OrganisationDTO | null = null;
private parentTeamsDefinitionId: string | null = null;
parentTeamsDataSource = new MatTableDataSource<FieldInstanceDTO>();
parentTeamsColumns = ['label', 'actions'];
parentTeamsForm = new FormGroup({
  labelDe: new FormControl('', Validators.required),
});
```

- [ ] **Step 2: `ngOnInit()` erweitern**

In `ngOnInit()` nach `this.loadDutySettings()`:
```typescript
this.loadParentTeams();
```

- [ ] **Step 3: `loadParentTeams()`, `addParentTeam()` und `deleteParentTeam()` hinzufügen**

Am Ende der Klasse (vor der schließenden `}`), nach `deleteFoodProperty()`:

```typescript
// --- Parent Teams ---

loadParentTeams(): void {
  this.orgService.getByTag('parent-teams').subscribe({
    next: (org) => {
      this.parentTeamsOrg = org;
      const templateDef = org.definitions.find((d) => d.fieldName === 'parent-team' && !d.outdatedAt);
      if (!templateDef) {
        this.parentTeamsDefinitionId = null;
        this.parentTeamsDataSource.data = [];
        return;
      }
      this.parentTeamsDefinitionId = templateDef.id!;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.parentTeamsDataSource.data = instances;
      });
    },
    error: () => {
      this.parentTeamsOrg = null;
      this.parentTeamsDefinitionId = null;
      this.parentTeamsDataSource.data = [];
    },
  });
}

addParentTeam(): void {
  if (!this.parentTeamsForm.valid) return;
  const labelDe = this.parentTeamsForm.value.labelDe!;
  const value = { label: labelDe };

  if (this.parentTeamsDefinitionId) {
    this.fieldInstanceService.create(this.parentTeamsDefinitionId, value).subscribe(() => {
      this.parentTeamsForm.reset();
      this.loadParentTeams();
    });
  } else {
    const templateDef: FieldDefinition = {
      fieldName: 'parent-team',
      label: { de: 'Elterneinteilung' },
      jsonSchema: { type: 'object', properties: { label: { type: 'string' } } },
      required: false,
    };
    this.fieldDefService.create(templateDef).pipe(
      switchMap((created) => {
        this.parentTeamsDefinitionId = created.id!;
        const org = this.parentTeamsOrg;
        const updatedIds = org
          ? [...org.definitions.map((d) => d.id!), created.id!]
          : [created.id!];
        return org
          ? this.orgService.update(org.id, { definitionIds: updatedIds }).pipe(
              switchMap(() => this.fieldInstanceService.create(created.id!, value))
            )
          : this.fieldInstanceService.create(created.id!, value);
      })
    ).subscribe(() => {
      this.parentTeamsForm.reset();
      this.loadParentTeams();
    });
  }
}

deleteParentTeam(instance: FieldInstanceDTO): void {
  this.fieldInstanceService.delete(instance.id!).subscribe(() => {
    this.loadParentTeams();
  });
}
```

**Hinweis zu `addParentTeam()`:** Beim ersten Team existiert noch kein `parent-teams`-Organisationseintrag. Die `getByTag`-Methode gibt in diesem Fall je nach Backend-Implementierung einen leeren Eintrag zurück (wie bei `groups`). Falls `parentTeamsOrg` null ist, wird kein `orgService.update()` aufgerufen — das Backend erzeugt den Eintrag beim ersten Zugriff implizit. Falls das Backend 404 zurückgibt (statt eines leeren Eintrags), muss `loadParentTeams()` die Fehlerbehandlung nutzen (bereits implementiert via `error:`-Handler) und `addParentTeam()` ohne `orgService.update()` arbeiten. Prüfe das Verhalten manuell beim ersten Team-Anlegen.

- [ ] **Step 4: Neuen Tab in `organisation.component.html` hinzufügen**

Am Ende von `<mat-tab-group>`, nach dem schließenden `</mat-tab>` des "Dienst-Einstellungen"-Tabs, vor `</mat-tab-group>`:

```html
<!-- Parent Teams Tab -->
<mat-tab label="Elterneinteilung">
  <div class="tab-content">
    <form [formGroup]="parentTeamsForm" (ngSubmit)="addParentTeam()" class="add-form">
      <div class="row">
        <mat-form-field appearance="outline">
          <mat-label>Teamname</mat-label>
          <input matInput formControlName="labelDe">
        </mat-form-field>
        <button mat-raised-button color="primary" type="submit" [disabled]="!parentTeamsForm.valid">
          Team hinzufuegen
        </button>
      </div>
    </form>

    <table mat-table [dataSource]="parentTeamsDataSource" class="mat-elevation-z2">
      <ng-container matColumnDef="label">
        <th mat-header-cell *matHeaderCellDef>Teamname</th>
        <td mat-cell *matCellDef="let row">{{ $any(row.value).label }}</td>
      </ng-container>
      <ng-container matColumnDef="actions">
        <th mat-header-cell *matHeaderCellDef></th>
        <td mat-cell *matCellDef="let row">
          <button mat-icon-button color="warn" (click)="deleteParentTeam(row)" title="Entfernen">
            <mat-icon>delete</mat-icon>
          </button>
        </td>
      </ng-container>
      <tr mat-header-row *matHeaderRowDef="parentTeamsColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: parentTeamsColumns;"></tr>
    </table>
  </div>
</mat-tab>
```

- [ ] **Step 5: Build prüfen**

```bash
cd frontend && npx ng build --configuration development 2>&1 | grep -E "error" | head -20
```

Erwartetes Ergebnis: keine Fehler.

- [ ] **Step 6: Manuell testen**

1. Starte Frontend + Backend
2. Navigiere zu `settings/organisation`
3. Tab "Elterneinteilung" erscheint
4. Team "Garten" anlegen → erscheint in Tabelle
5. Team löschen → verschwindet

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/settings/organisation/organisation.component.ts \
        frontend/src/app/settings/organisation/organisation.component.html
git commit -m "feat: add Elterneinteilung tab to OrganisationComponent for parent team management"
```

---

### Task 4: Frontend — ElterneinteilungComponent + Route

**Files:**
- Create: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts`
- Create: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html`
- Create: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.scss`
- Modify: `frontend/src/app/app.routes.ts`

**Interfaces:**
- Consumes: `PersonService.list()`, `PersonService.getFull(id)`, `PersonService.assignTeam()`, `OrganisationService.getByTag('parent-teams')`, `FieldInstanceService.listByDefinitionId()`
- Consumes: `PersonDTO.assignedDuty: FieldInstanceDTO[]` (aus Task 2)
- Consumes: `PATCH /persons/{id}/assigned-duty` (aus Task 1)

- [ ] **Step 1: `elterneinteilung.component.ts` erstellen**

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { forkJoin, of } from 'rxjs';
import { switchMap, catchError } from 'rxjs/operators';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

interface ParentRow {
  person: PersonDTO;
  name: string;
}

@Component({
  selector: 'app-elterneinteilung',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatTableModule, MatChipsModule,
    MatFormFieldModule, MatSelectModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './elterneinteilung.component.html',
  styleUrl: './elterneinteilung.component.scss',
})
export class ElterneinteilungComponent implements OnInit {
  teams: FieldInstanceDTO[] = [];
  parentTeamsDefinitionId: string | null = null;
  allParents: ParentRow[] = [];
  displayedParents: ParentRow[] = [];
  filterTeamId: string | null = null;
  loading = false;
  displayedColumns = ['name', 'teams'];

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.orgService.getByTag('parent-teams').pipe(
      catchError(() => of({ id: '', tag: 'parent-teams', definitions: [], entries: [] })),
      switchMap((org) => {
        const templateDef = org.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team' && !d.outdatedAt
        );
        if (!templateDef) {
          this.teams = [];
          this.parentTeamsDefinitionId = null;
          return of([] as FieldInstanceDTO[]);
        }
        this.parentTeamsDefinitionId = templateDef.id!;
        return this.fieldInstanceService.listByDefinitionId(templateDef.id!);
      }),
      switchMap((teams) => {
        this.teams = teams;
        return this.personService.list();
      }),
      switchMap((persons) => {
        if (persons.length === 0) return of([] as PersonDTO[]);
        return forkJoin(persons.map(p => this.personService.getFull(p.id!)));
      }),
    ).subscribe({
      next: (fullPersons) => {
        this.allParents = fullPersons
          .filter(p => !this.isChild(p))
          .map(p => ({ person: p, name: this.getPersonName(p) }));
        this.applyFilter();
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  private isChild(person: PersonDTO): boolean {
    return person.basicProperties.some(
      f => f.fieldName === 'personType' && f.value === 'CHILD'
    );
  }

  private getPersonName(person: PersonDTO): string {
    const firstName = person.basicProperties.find(f => f.fieldName === 'firstName')?.value as string ?? '';
    const lastName = person.basicProperties.find(f => f.fieldName === 'lastName')?.value as string ?? '';
    return `${firstName} ${lastName}`.trim() || person.id;
  }

  isAssigned(person: PersonDTO, team: FieldInstanceDTO): boolean {
    return (person.assignedDuty ?? []).some(d => d.id === team.id);
  }

  toggleTeam(row: ParentRow, team: FieldInstanceDTO): void {
    if (!this.parentTeamsDefinitionId) return;
    this.personService.assignTeam(row.person.id, this.parentTeamsDefinitionId, team.id!).subscribe(() => {
      if (this.isAssigned(row.person, team)) {
        row.person.assignedDuty = row.person.assignedDuty.filter(d => d.id !== team.id);
      } else {
        row.person.assignedDuty = [...(row.person.assignedDuty ?? []), team];
      }
      this.applyFilter();
    });
  }

  onFilterChange(): void {
    this.applyFilter();
  }

  private applyFilter(): void {
    if (!this.filterTeamId) {
      this.displayedParents = [...this.allParents];
    } else {
      this.displayedParents = this.allParents.filter(row =>
        (row.person.assignedDuty ?? []).some(d => d.id === this.filterTeamId)
      );
    }
  }

  getTeamLabel(team: FieldInstanceDTO): string {
    return (team.value as { label: string })?.label ?? team.id ?? '';
  }
}
```

- [ ] **Step 2: `elterneinteilung.component.html` erstellen**

```html
<div class="elterneinteilung-container">
  <h2>Elterneinteilung</h2>

  <div *ngIf="loading" class="loading">
    <mat-spinner diameter="40"></mat-spinner>
  </div>

  <ng-container *ngIf="!loading">
    <div class="filter-row" *ngIf="teams.length > 0">
      <mat-form-field appearance="outline">
        <mat-label>Filter nach Team</mat-label>
        <mat-select [(ngModel)]="filterTeamId" (ngModelChange)="onFilterChange()">
          <mat-option [value]="null">Alle anzeigen</mat-option>
          <mat-option *ngFor="let team of teams" [value]="team.id">
            {{ getTeamLabel(team) }}
          </mat-option>
        </mat-select>
      </mat-form-field>
    </div>

    <p *ngIf="teams.length === 0" class="empty-state">
      Keine Teams konfiguriert. Bitte zuerst Teams unter
      <strong>Einstellungen &gt; Organisation &gt; Elterneinteilung</strong> anlegen.
    </p>

    <table mat-table [dataSource]="displayedParents" class="mat-elevation-z2" *ngIf="teams.length > 0">
      <ng-container matColumnDef="name">
        <th mat-header-cell *matHeaderCellDef>Elternteil</th>
        <td mat-cell *matCellDef="let row">{{ row.name }}</td>
      </ng-container>

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
        </td>
      </ng-container>

      <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
      <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
    </table>

    <p *ngIf="teams.length > 0 && displayedParents.length === 0" class="empty-state">
      Keine Eltern gefunden.
    </p>
  </ng-container>
</div>
```

- [ ] **Step 3: `elterneinteilung.component.scss` erstellen**

```scss
.elterneinteilung-container {
  padding: 24px;
}

.loading {
  display: flex;
  justify-content: center;
  padding: 48px;
}

.filter-row {
  margin-bottom: 16px;
}

.empty-state {
  color: rgba(0, 0, 0, 0.54);
  text-align: center;
  padding: 24px;
}

.chip-assigned {
  background-color: #1976d2 !important;
  color: white !important;
  cursor: pointer;
}

mat-chip {
  cursor: pointer;
}
```

- [ ] **Step 4: Route in `app.routes.ts` hinzufügen**

In `app.routes.ts`, innerhalb des `administration`-Children-Arrays, nach dem `platzzuweisung`-Eintrag:

```typescript
{
  path: 'elterneinteilung',
  loadComponent: () =>
    import('./administration/elterneinteilung/elterneinteilung.component').then(
      m => m.ElterneinteilungComponent
    ),
},
```

- [ ] **Step 5: Build prüfen**

```bash
cd frontend && npx ng build --configuration development 2>&1 | grep -E "error" | head -20
```

Erwartetes Ergebnis: keine Fehler.

- [ ] **Step 6: Manuell End-to-End testen**

1. Starte Frontend + Backend
2. Mindestens 1 Team unter `settings/organisation > Elterneinteilung` anlegen (z.B. "Garten")
3. Navigiere zu `administration/elterneinteilung`
4. Liste der Eltern erscheint mit Team-Chips
5. Chip "Garten" klicken → blau hervorgehoben → Elternteil ist Team zugeordnet
6. Nochmals klicken → Chip nicht mehr hervorgehoben → Zuweisung aufgehoben
7. Filter "Garten" auswählen → nur zugeordnete Eltern sichtbar
8. Backend prüfen: `GET /api/v1/persons/{id}/full` → `assignedDuty` enthält die Team-FieldInstance

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/administration/elterneinteilung/ \
        frontend/src/app/app.routes.ts
git commit -m "feat: add Elterneinteilung admin screen for parent team assignment"
```
