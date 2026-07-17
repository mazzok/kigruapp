# Elterneinteilung Rollen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rollen innerhalb von Elterneinteilungs-Teams — konfigurierbar in den Einstellungen, zuweisbar im Admin-Screen mit Max-Limit-Anzeige und Bestätigungs-Dialog beim Team-Abwählen.

**Architecture:** Rollen sind FieldInstances unter einem neuen Org-Tag `parent-team-roles`. `Person` bekommt eine neue Sektion `assignedRole: FieldRef[]`, getrennt von `assignedDuty` (Teams). Der ElterneinteilungComponent zeigt eine neue Spalte "Rollen" mit Chips — nur Rollen der zugewiesenen Teams sind sichtbar. Max-Überprüfung erfolgt im Frontend.

**Tech Stack:** Quarkus + MongoDB Panache (Backend), Angular 17 + Angular Material (Frontend), standalone components, reactive forms.

## Global Constraints

- Backend-Package: `at.kigruapp` (nicht `de.kigruapp`)
- FieldInstance-value für Rollen: `{ label: string, teamInstanceId: string, min?: number, max?: number }`
- fieldName für Rollen-Definition: `'parent-team-role'`
- Org-Tag für Rollen: `'parent-team-roles'`
- Neuer Person-Endpoint: `PATCH /api/v1/persons/{id}/assigned-role`
- Toggle-Logik identisch zu `assigned-duty`: wenn FieldInstance bereits in Liste → entfernen, sonst → hinzufügen
- Max-Prüfung nur im Frontend (kein Backend-Enforcement)
- Angular: standalone components, `*ngIf` / `*ngFor` via `CommonModule`
- Angular Material Chips: `mat-chip` mit `[disabled]` und `[matTooltip]`

---

### Task 1: Backend — `assignedRole` auf Person + PATCH-Endpoint

**Files:**
- Modify: `backend/src/main/java/at/kigruapp/entity/Person.java`
- Modify: `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`
- Modify: `backend/src/main/java/at/kigruapp/resource/PersonResource.java`

**Interfaces:**
- Produces: `Person.assignedRole: List<FieldRef>`
- Produces: `PersonDTO.assignedRole: List<FieldInstanceDTO>`
- Produces: `PATCH /api/v1/persons/{id}/assigned-role` → togglet FieldRef in `assignedRole`

- [ ] **Step 1: `assignedRole` zu `Person.java` hinzufügen**

In `backend/src/main/java/at/kigruapp/entity/Person.java`, nach `assignedDuty`-Zeile einfügen:

```java
public List<FieldRef> assignedRole = new ArrayList<>();
```

Vollständige Datei nach Änderung (nur relevanter Block):
```java
public List<FieldRef> organisationalUnit = new ArrayList<>();
public List<FieldRef> assignedDuty = new ArrayList<>();
public List<FieldRef> assignedRole = new ArrayList<>();
public Instant createdAt;
```

- [ ] **Step 2: `assignedRole` zu `PersonDTO.java` hinzufügen**

In `backend/src/main/java/at/kigruapp/dto/PersonDTO.java`, nach `assignedDuty`-Zeile einfügen:

```java
public List<FieldInstanceDTO> assignedRole;
```

Vollständige Datei nach Änderung:
```java
package at.kigruapp.dto;

import java.util.List;

public class PersonDTO {
    public String id;
    public String familyId;
    public String keycloakUserId;
    public List<FieldInstanceDTO> basicProperties;
    public List<FieldInstanceDTO> roles;
    public List<FieldInstanceDTO> schedules;
    public List<FieldInstanceDTO> duties;
    public List<FieldInstanceDTO> finance;
    public List<FieldInstanceDTO> customProperties;
    public List<FieldInstanceDTO> organisationalUnit;
    public List<FieldInstanceDTO> assignedDuty;
    public List<FieldInstanceDTO> assignedRole;
    public String createdAt;
    public String updatedAt;
}
```

- [ ] **Step 3: `RoleAssignmentRequest`-Record und PATCH-Endpoint zu `PersonResource.java` hinzufügen**

In `PersonResource.java`:

**3a.** Nach dem `TeamAssignmentRequest`-Record hinzufügen:
```java
public record RoleAssignmentRequest(String definitionId, String fieldInstanceId) {}
```

**3b.** Nach der `patchAssignedDuty`-Methode hinzufügen:
```java
@PATCH
@Path("/{id}/assigned-role")
public Response patchAssignedRole(@PathParam("id") String id, RoleAssignmentRequest request) {
    Person person = Person.findById(new ObjectId(id));
    if (person == null) throw new NotFoundException();

    ObjectId defId = new ObjectId(request.definitionId());
    ObjectId instId = new ObjectId(request.fieldInstanceId());

    if (person.assignedRole == null) {
        person.assignedRole = new ArrayList<>();
    }

    boolean removed = person.assignedRole.removeIf(ref -> ref.fieldInstanceId.equals(instId));
    if (!removed) {
        person.assignedRole.add(new FieldRef(defId, instId));
    }

    person.updatedAt = Instant.now();
    person.update();
    return Response.noContent().build();
}
```

**3c.** In der `toFullDTO()`-Methode, nach der `assignedDuty`-Zeile einfügen:
```java
dto.assignedRole = resolveRefs(person.assignedRole != null ? person.assignedRole : List.of());
```

- [ ] **Step 4: Backend kompilieren**

```bash
cd D:/GIT/kigruapp/backend && ./mvnw compile -q 2>&1 | tail -20
```

Erwartetes Ergebnis: `BUILD SUCCESS`, keine Fehler.

- [ ] **Step 5: Commit**

```bash
cd D:/GIT/kigruapp && git add backend/src/main/java/at/kigruapp/entity/Person.java \
  backend/src/main/java/at/kigruapp/dto/PersonDTO.java \
  backend/src/main/java/at/kigruapp/resource/PersonResource.java
git commit -m "feat: add assignedRole to Person entity + PATCH /persons/{id}/assigned-role endpoint"
```

---

### Task 2: Frontend — Person-Modell + PersonService

**Files:**
- Modify: `frontend/src/app/shared/models/person.model.ts`
- Modify: `frontend/src/app/shared/services/person.service.ts`

**Interfaces:**
- Produces: `PersonDTO.assignedRole: FieldInstanceDTO[]`
- Produces: `PersonService.assignRole(personId, definitionId, fieldInstanceId): Observable<void>`

- [ ] **Step 1: `assignedRole` zu `Person`- und `PersonDTO`-Interfaces hinzufügen**

In `frontend/src/app/shared/models/person.model.ts`:

Im `Person`-Interface nach `assignedDuty: FieldRef[];` einfügen:
```typescript
assignedRole: FieldRef[];
```

Im `PersonDTO`-Interface nach `assignedDuty: FieldInstanceDTO[];` einfügen:
```typescript
assignedRole: FieldInstanceDTO[];
```

Vollständige Interfaces nach Änderung:
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
  assignedRole: FieldRef[];
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
  assignedRole: FieldInstanceDTO[];
  createdAt?: string;
  updatedAt?: string;
}
```

- [ ] **Step 2: `assignRole()` zu `PersonService` hinzufügen**

In `frontend/src/app/shared/services/person.service.ts`, nach der `assignTeam()`-Methode einfügen:

```typescript
assignRole(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
  return this.api.patch<void>(`/persons/${personId}/assigned-role`, { definitionId, fieldInstanceId });
}
```

- [ ] **Step 3: Build prüfen**

```bash
cd D:/GIT/kigruapp/frontend && npx ng build --configuration development 2>&1 | grep -E "^.*error" | head -20
```

Erwartetes Ergebnis: keine Typfehler.

- [ ] **Step 4: Commit**

```bash
cd D:/GIT/kigruapp && git add frontend/src/app/shared/models/person.model.ts \
  frontend/src/app/shared/services/person.service.ts
git commit -m "feat: add assignedRole to Person model and assignRole() to PersonService"
```

---

### Task 3: Frontend — OrganisationComponent — Ausklappbare Teams mit Rollen-Sub-Tabelle

**Files:**
- Modify: `frontend/src/app/settings/organisation/organisation.component.ts`
- Modify: `frontend/src/app/settings/organisation/organisation.component.html`

**Interfaces:**
- Consumes: `OrganisationService.getByTag('parent-team-roles')`
- Consumes: `FieldInstanceService.listByDefinitionId()`, `FieldInstanceService.create()`, `FieldInstanceService.delete()`
- Consumes: `FieldDefinitionService.create()`

- [ ] **Step 1: `organisation.component.ts` vollständig ersetzen**

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { IconPickerDialogComponent } from '../../shared/components/icon-picker/icon-picker-dialog.component';
import { switchMap } from 'rxjs/operators';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { OrganisationDTO, DutyEntryDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';

@Component({
  selector: 'app-organisation',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatTabsModule, MatTableModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatIconModule,
    MatExpansionModule, MatDialogModule, IconPickerDialogComponent,
  ],
  templateUrl: './organisation.component.html',
  styleUrl: './organisation.component.scss',
})
export class OrganisationComponent implements OnInit {
  // Groups tab
  groupsOrg: OrganisationDTO | null = null;
  private groupDefinitionId: string | null = null;
  groupsDataSource = new MatTableDataSource<FieldInstanceDTO>();
  groupColumns = ['label', 'color', 'actions'];
  groupForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    color: new FormControl('#4285f4', Validators.required),
  });

  // Parent Teams tab
  parentTeamsOrg: OrganisationDTO | null = null;
  private parentTeamsDefinitionId: string | null = null;
  parentTeams: FieldInstanceDTO[] = [];
  parentTeamsForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
  });

  // Parent Team Roles
  private parentTeamRolesOrg: OrganisationDTO | null = null;
  parentTeamRolesDefinitionId: string | null = null;
  rolesByTeamId: Map<string, FieldInstanceDTO[]> = new Map();
  roleColumns = ['label', 'min', 'max', 'roleActions'];
  addRoleForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    min: new FormControl<number | null>(null),
    max: new FormControl<number | null>(null),
  });

  // Duty settings tab
  dutySettingsOrg: OrganisationDTO | null = null;
  cookingDataSource = new MatTableDataSource<FieldDefinition>();
  dutyColumns = ['label', 'icon', 'actions'];
  dutyForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    icon: new FormControl('restaurant', Validators.required),
  });

  constructor(
    private orgService: OrganisationService,
    private fieldDefService: FieldDefinitionService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadGroups();
    this.loadDutySettings();
    this.loadParentTeams();
  }

  // --- Groups ---

  loadGroups(): void {
    this.orgService.getByTag('groups').subscribe((org) => {
      this.groupsOrg = org;
      const templateDef = org.definitions.find((d) => d.fieldName === 'group' && !d.outdatedAt);
      if (!templateDef) {
        this.groupDefinitionId = null;
        this.groupsDataSource.data = [];
        return;
      }
      this.groupDefinitionId = templateDef.id!;
      this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
        this.groupsDataSource.data = instances;
      });
    });
  }

  addGroup(): void {
    if (!this.groupForm.valid || !this.groupsOrg) return;
    const labelDe = this.groupForm.value.labelDe!;
    const color = this.groupForm.value.color!;
    const value = { label: labelDe, color };

    if (this.groupDefinitionId) {
      this.fieldInstanceService.create(this.groupDefinitionId, value).subscribe(() => {
        this.groupForm.reset({ color: '#4285f4' });
        this.loadGroups();
      });
    } else {
      const templateDef: FieldDefinition = {
        fieldName: 'group',
        label: { de: 'Gruppen' },
        jsonSchema: { type: 'object', properties: { label: { type: 'string' }, color: { type: 'string' } } },
        required: false,
      };
      this.fieldDefService.create(templateDef).pipe(
        switchMap((created) => {
          this.groupDefinitionId = created.id!;
          const updatedIds = [...this.groupsOrg!.definitions.map((d) => d.id!), created.id!];
          return this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).pipe(
            switchMap(() => this.fieldInstanceService.create(created.id!, value))
          );
        })
      ).subscribe(() => {
        this.groupForm.reset({ color: '#4285f4' });
        this.loadGroups();
      });
    }
  }

  deleteGroup(instance: FieldInstanceDTO): void {
    this.fieldInstanceService.delete(instance.id!).subscribe(() => {
      this.loadGroups();
    });
  }

  // --- Duty Settings (Cooking) ---

  loadDutySettings(): void {
    this.orgService.getByTag('duty-settings').subscribe((org) => {
      this.dutySettingsOrg = org;
      const cookingEntry = org.entries.find((e) => e.name === 'cooking');
      this.cookingDataSource.data = cookingEntry?.definitions ?? [];
    });
  }

  addFoodProperty(): void {
    if (!this.dutyForm.valid || !this.dutySettingsOrg) return;
    const labelDe = this.dutyForm.value.labelDe!;
    const icon = this.dutyForm.value.icon!;

    const newDef: FieldDefinition = {
      fieldName: 'food-property',
      label: { de: labelDe },
      jsonSchema: { type: 'boolean' },
      required: false,
      properties: { icon },
    };

    this.fieldDefService.create(newDef).subscribe((created) => {
      const entries = this.dutySettingsOrg!.entries.map((e) => {
        const defIds = e.definitions.map((d) => d.id!);
        if (e.name === 'cooking') {
          defIds.push(created.id!);
        }
        return { name: e.name, definitionIds: defIds };
      });
      this.orgService.update(this.dutySettingsOrg!.id, { definitionIds: [], entries }).subscribe(() => {
        this.dutyForm.reset({ icon: 'restaurant' });
        this.loadDutySettings();
      });
    });
  }

  openIconPicker(): void {
    this.dialog.open(IconPickerDialogComponent, { width: '620px' })
      .afterClosed()
      .subscribe(iconName => {
        if (iconName) {
          this.dutyForm.get('icon')!.setValue(iconName);
        }
      });
  }

  deleteFoodProperty(def: FieldDefinition): void {
    if (!this.dutySettingsOrg) return;
    this.fieldDefService.outdate(def.id!).subscribe(() => {
      const entries = this.dutySettingsOrg!.entries.map((e) => ({
        name: e.name,
        definitionIds: e.definitions.filter((d) => d.id !== def.id).map((d) => d.id!),
      }));
      this.orgService.update(this.dutySettingsOrg!.id, { definitionIds: [], entries }).subscribe(() => {
        this.loadDutySettings();
      });
    });
  }

  // --- Parent Teams ---

  loadParentTeams(): void {
    this.orgService.getByTag('parent-teams').subscribe({
      next: (org) => {
        this.parentTeamsOrg = org;
        const templateDef = org.definitions.find((d) => d.fieldName === 'parent-team' && !d.outdatedAt);
        if (!templateDef) {
          this.parentTeamsDefinitionId = null;
          this.parentTeams = [];
          return;
        }
        this.parentTeamsDefinitionId = templateDef.id!;
        this.fieldInstanceService.listByDefinitionId(templateDef.id!).subscribe((instances) => {
          this.parentTeams = instances;
          this.loadParentTeamRoles();
        });
      },
      error: () => {
        this.parentTeamsOrg = null;
        this.parentTeamsDefinitionId = null;
        this.parentTeams = [];
      },
    });
  }

  addParentTeam(): void {
    if (!this.parentTeamsForm.valid || !this.parentTeamsOrg) return;
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
          const updatedIds = [...this.parentTeamsOrg!.definitions.map((d) => d.id!), created.id!];
          return this.orgService.update(this.parentTeamsOrg!.id, { definitionIds: updatedIds }).pipe(
            switchMap(() => this.fieldInstanceService.create(created.id!, value))
          );
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

  // --- Parent Team Roles ---

  loadParentTeamRoles(): void {
    this.orgService.getByTag('parent-team-roles').subscribe({
      next: (org) => {
        this.parentTeamRolesOrg = org;
        const def = org.definitions.find((d) => d.fieldName === 'parent-team-role' && !d.outdatedAt);
        this.parentTeamRolesDefinitionId = def?.id ?? null;
        if (!def) {
          this.rolesByTeamId = new Map();
          return;
        }
        this.fieldInstanceService.listByDefinitionId(def.id!).subscribe((roles) => {
          const map = new Map<string, FieldInstanceDTO[]>();
          for (const role of roles) {
            const teamId = (role.value as Record<string, unknown>)?.['teamInstanceId'] as string;
            if (teamId) {
              if (!map.has(teamId)) map.set(teamId, []);
              map.get(teamId)!.push(role);
            }
          }
          this.rolesByTeamId = map;
        });
      },
      error: () => {
        this.parentTeamRolesOrg = null;
        this.parentTeamRolesDefinitionId = null;
        this.rolesByTeamId = new Map();
      },
    });
  }

  getRolesForTeam(team: FieldInstanceDTO): FieldInstanceDTO[] {
    return this.rolesByTeamId.get(team.id!) ?? [];
  }

  addRole(team: FieldInstanceDTO): void {
    if (!this.addRoleForm.valid) return;
    const { labelDe, min, max } = this.addRoleForm.value;
    const value: Record<string, unknown> = { label: labelDe!, teamInstanceId: team.id! };
    if (min != null) value['min'] = min;
    if (max != null) value['max'] = max;

    const doCreate = (defId: string) => {
      this.fieldInstanceService.create(defId, value).subscribe(() => {
        this.addRoleForm.reset();
        this.loadParentTeamRoles();
      });
    };

    if (this.parentTeamRolesDefinitionId) {
      doCreate(this.parentTeamRolesDefinitionId);
    } else {
      const templateDef: FieldDefinition = {
        fieldName: 'parent-team-role',
        label: { de: 'Elterneinteilung Rolle' },
        jsonSchema: {
          type: 'object',
          properties: {
            label: { type: 'string' },
            teamInstanceId: { type: 'string' },
            min: { type: 'number' },
            max: { type: 'number' },
          },
        },
        required: false,
      };
      this.fieldDefService.create(templateDef).pipe(
        switchMap((created) => {
          this.parentTeamRolesDefinitionId = created.id!;
          const org = this.parentTeamRolesOrg;
          if (org) {
            const updatedIds = [...org.definitions.map((d) => d.id!), created.id!];
            return this.orgService.update(org.id, { definitionIds: updatedIds }).pipe(
              switchMap(() => this.fieldInstanceService.create(created.id!, value))
            );
          }
          return this.fieldInstanceService.create(created.id!, value);
        })
      ).subscribe(() => {
        this.addRoleForm.reset();
        this.loadParentTeamRoles();
      });
    }
  }

  deleteRole(role: FieldInstanceDTO): void {
    this.fieldInstanceService.delete(role.id!).subscribe(() => {
      this.loadParentTeamRoles();
    });
  }

  onPanelOpened(): void {
    this.addRoleForm.reset();
  }
}
```

- [ ] **Step 2: `organisation.component.html` vollständig ersetzen**

```html
<div class="organisation-container">
  <h2>Organisation</h2>

  <mat-tab-group>
    <!-- Groups Tab -->
    <mat-tab label="Gruppen">
      <div class="tab-content">
        <form [formGroup]="groupForm" (ngSubmit)="addGroup()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Gruppenname</mat-label>
              <input matInput formControlName="labelDe">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Farbe</mat-label>
              <input matInput formControlName="color" type="color">
            </mat-form-field>
            <button mat-raised-button color="primary" type="submit" [disabled]="!groupForm.valid">
              Gruppe hinzufuegen
            </button>
          </div>
        </form>

        <table mat-table [dataSource]="groupsDataSource" class="mat-elevation-z2">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let row">{{ $any(row.value).label }}</td>
          </ng-container>
          <ng-container matColumnDef="color">
            <th mat-header-cell *matHeaderCellDef>Farbe</th>
            <td mat-cell *matCellDef="let row">
              <span class="color-swatch" [style.background-color]="$any(row.value).color"></span>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="warn" (click)="deleteGroup(row)" title="Entfernen">
                <mat-icon>delete</mat-icon>
              </button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="groupColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: groupColumns;"></tr>
        </table>
      </div>
    </mat-tab>

    <!-- Duty Settings Tab -->
    <mat-tab label="Dienst-Einstellungen">
      <div class="tab-content">
        <h3>Kochdienst — Essen-Eigenschaften</h3>

        <form [formGroup]="dutyForm" (ngSubmit)="addFoodProperty()" class="add-form">
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Bezeichnung</mat-label>
              <input matInput formControlName="labelDe">
            </mat-form-field>
            <div style="display: flex; align-items: center; gap: 8px;">
              <mat-form-field style="flex: 1;" appearance="outline">
                <mat-label>Icon (Material Icon)</mat-label>
                <input matInput formControlName="icon">
              </mat-form-field>
              <button mat-icon-button type="button" (click)="openIconPicker()" title="Icon auswaehlen">
                <mat-icon>image_search</mat-icon>
              </button>
            </div>
            <button mat-raised-button color="primary" type="submit" [disabled]="!dutyForm.valid">
              Eigenschaft hinzufuegen
            </button>
          </div>
        </form>

        <table mat-table [dataSource]="cookingDataSource" class="mat-elevation-z2">
          <ng-container matColumnDef="label">
            <th mat-header-cell *matHeaderCellDef>Bezeichnung</th>
            <td mat-cell *matCellDef="let row">{{ row.label['de'] }}</td>
          </ng-container>
          <ng-container matColumnDef="icon">
            <th mat-header-cell *matHeaderCellDef>Icon</th>
            <td mat-cell *matCellDef="let row">
              <mat-icon>{{ row.properties?.icon }}</mat-icon>
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="warn" (click)="deleteFoodProperty(row)" title="Entfernen">
                <mat-icon>delete</mat-icon>
              </button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="dutyColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: dutyColumns;"></tr>
        </table>
      </div>
    </mat-tab>

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

        <p *ngIf="parentTeams.length === 0" class="empty-state">Noch keine Teams angelegt.</p>

        <mat-accordion *ngIf="parentTeams.length > 0">
          <mat-expansion-panel
            *ngFor="let team of parentTeams"
            (opened)="onPanelOpened()">
            <mat-expansion-panel-header>
              <mat-panel-title>{{ $any(team.value).label }}</mat-panel-title>
              <mat-panel-description>
                <button mat-icon-button color="warn"
                  (click)="deleteParentTeam(team); $event.stopPropagation()"
                  title="Team entfernen">
                  <mat-icon>delete</mat-icon>
                </button>
              </mat-panel-description>
            </mat-expansion-panel-header>

            <!-- Rollen Sub-Tabelle -->
            <table mat-table [dataSource]="getRolesForTeam(team)"
              class="roles-table mat-elevation-z1"
              *ngIf="getRolesForTeam(team).length > 0">
              <ng-container matColumnDef="label">
                <th mat-header-cell *matHeaderCellDef>Rolle</th>
                <td mat-cell *matCellDef="let role">{{ $any(role.value).label }}</td>
              </ng-container>
              <ng-container matColumnDef="min">
                <th mat-header-cell *matHeaderCellDef>Min</th>
                <td mat-cell *matCellDef="let role">{{ $any(role.value).min ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="max">
                <th mat-header-cell *matHeaderCellDef>Max</th>
                <td mat-cell *matCellDef="let role">{{ $any(role.value).max ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="roleActions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let role">
                  <button mat-icon-button color="warn" (click)="deleteRole(role)" title="Rolle entfernen">
                    <mat-icon>delete</mat-icon>
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="roleColumns"></tr>
              <tr mat-row *matRowDef="let row; columns: roleColumns;"></tr>
            </table>

            <!-- Rolle hinzufügen -->
            <form [formGroup]="addRoleForm" (ngSubmit)="addRole(team)" class="add-role-form">
              <mat-form-field appearance="outline">
                <mat-label>Rollenname</mat-label>
                <input matInput formControlName="labelDe">
              </mat-form-field>
              <mat-form-field appearance="outline" class="short-field">
                <mat-label>Min</mat-label>
                <input matInput formControlName="min" type="number" min="0">
              </mat-form-field>
              <mat-form-field appearance="outline" class="short-field">
                <mat-label>Max</mat-label>
                <input matInput formControlName="max" type="number" min="0">
              </mat-form-field>
              <button mat-raised-button color="accent" type="submit" [disabled]="!addRoleForm.valid">
                Rolle hinzufuegen
              </button>
            </form>
          </mat-expansion-panel>
        </mat-accordion>
      </div>
    </mat-tab>
  </mat-tab-group>
</div>
```

- [ ] **Step 3: Build prüfen**

```bash
cd D:/GIT/kigruapp/frontend && npx ng build --configuration development 2>&1 | grep -E "^.*error" | head -20
```

Erwartetes Ergebnis: keine Fehler.

- [ ] **Step 4: Manuell testen**

1. Navigation: Einstellungen → Organisation → Tab "Elterneinteilung"
2. Team anlegen (z.B. "Garten") → erscheint als `mat-expansion-panel`
3. Team aufklappen → Formular "Rolle hinzufügen" erscheint
4. Rolle anlegen (z.B. "Spielplatz", Min: 1, Max: 2) → erscheint in Sub-Tabelle
5. Rolle löschen → verschwindet
6. Team löschen → panel verschwindet

- [ ] **Step 5: Commit**

```bash
cd D:/GIT/kigruapp && git add frontend/src/app/settings/organisation/organisation.component.ts \
  frontend/src/app/settings/organisation/organisation.component.html
git commit -m "feat: add expandable teams with role sub-table to OrganisationComponent Elterneinteilung tab"
```

---

### Task 4: Frontend — ConfirmDialogComponent + ElterneinteilungComponent Rollen-Spalte

**Files:**
- Create: `frontend/src/app/shared/components/confirm-dialog/confirm-dialog.component.ts`
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts`
- Modify: `frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html`

**Interfaces:**
- Consumes: `PersonService.assignRole(personId, definitionId, fieldInstanceId)` (aus Task 2)
- Consumes: `PersonDTO.assignedRole: FieldInstanceDTO[]` (aus Task 2)
- Consumes: `OrganisationService.getByTag('parent-team-roles')`
- Consumes: `FieldInstanceService.listByDefinitionId()`

- [ ] **Step 1: `ConfirmDialogComponent` erstellen**

Neue Datei `frontend/src/app/shared/components/confirm-dialog/confirm-dialog.component.ts`:

```typescript
import { Component, Inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Bestätigung</h2>
    <mat-dialog-content>{{ data.message }}</mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="dialogRef.close(false)">Nein</button>
      <button mat-raised-button color="warn" (click)="dialogRef.close(true)">Ja</button>
    </mat-dialog-actions>
  `,
})
export class ConfirmDialogComponent {
  constructor(
    public dialogRef: MatDialogRef<ConfirmDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: { message: string },
  ) {}
}
```

- [ ] **Step 2: `elterneinteilung.component.ts` vollständig ersetzen**

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { forkJoin, of } from 'rxjs';
import { switchMap, catchError } from 'rxjs/operators';
import { PersonService } from '../../shared/services/person.service';
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldInstanceService } from '../../shared/services/field-instance.service';
import { PersonDTO } from '../../shared/models/person.model';
import { FieldInstanceDTO } from '../../shared/models/field-instance.model';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog/confirm-dialog.component';

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
    MatProgressSpinnerModule, MatTooltipModule,
    MatDialogModule,
  ],
  templateUrl: './elterneinteilung.component.html',
  styleUrl: './elterneinteilung.component.scss',
})
export class ElterneinteilungComponent implements OnInit {
  teams: FieldInstanceDTO[] = [];
  roles: FieldInstanceDTO[] = [];
  parentTeamsDefinitionId: string | null = null;
  parentTeamRolesDefinitionId: string | null = null;
  allParents: ParentRow[] = [];
  displayedParents: ParentRow[] = [];
  filterTeamId: string | null = null;
  loading = false;
  displayedColumns = ['name', 'teams', 'rollen'];

  constructor(
    private personService: PersonService,
    private orgService: OrganisationService,
    private fieldInstanceService: FieldInstanceService,
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    forkJoin({
      teamsOrg: this.orgService.getByTag('parent-teams').pipe(
        catchError(() => of({ id: '', tag: 'parent-teams', definitions: [], entries: [] } as any))
      ),
      rolesOrg: this.orgService.getByTag('parent-team-roles').pipe(
        catchError(() => of({ id: '', tag: 'parent-team-roles', definitions: [], entries: [] } as any))
      ),
    }).pipe(
      switchMap(({ teamsOrg, rolesOrg }) => {
        const teamDef = teamsOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team' && !d.outdatedAt
        );
        const roleDef = rolesOrg.definitions.find(
          (d: { fieldName: string; outdatedAt?: string | null }) =>
            d.fieldName === 'parent-team-role' && !d.outdatedAt
        );
        this.parentTeamsDefinitionId = teamDef?.id ?? null;
        this.parentTeamRolesDefinitionId = roleDef?.id ?? null;

        const teams$ = teamDef
          ? this.fieldInstanceService.listByDefinitionId(teamDef.id!)
          : of([] as FieldInstanceDTO[]);
        const roles$ = roleDef
          ? this.fieldInstanceService.listByDefinitionId(roleDef.id!)
          : of([] as FieldInstanceDTO[]);

        return forkJoin({ teams: teams$, roles: roles$ });
      }),
      switchMap(({ teams, roles }) => {
        this.teams = teams;
        this.roles = roles;
        return this.personService.list();
      }),
      switchMap((persons) => {
        if (persons.length === 0) return of([] as PersonDTO[]);
        return forkJoin(persons.map((p) => this.personService.getFull(p.id!)));
      }),
    ).subscribe({
      next: (fullPersons) => {
        this.allParents = fullPersons
          .filter((p) => !this.isChild(p))
          .map((p) => ({ person: p, name: this.getPersonName(p) }));
        this.applyFilter();
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  private isChild(person: PersonDTO): boolean {
    return person.basicProperties.some(
      (f) => f.fieldName === 'personType' && f.value === 'CHILD'
    );
  }

  private getPersonName(person: PersonDTO): string {
    const firstName = person.basicProperties.find((f) => f.fieldName === 'firstName')?.value as string ?? '';
    const lastName = person.basicProperties.find((f) => f.fieldName === 'lastName')?.value as string ?? '';
    return `${firstName} ${lastName}`.trim() || person.id!;
  }

  isAssigned(person: PersonDTO, team: FieldInstanceDTO): boolean {
    return (person.assignedDuty ?? []).some((d) => d.id === team.id);
  }

  isRoleAssigned(person: PersonDTO, role: FieldInstanceDTO): boolean {
    return (person.assignedRole ?? []).some((r) => r.id === role.id);
  }

  getVisibleRoles(person: PersonDTO): FieldInstanceDTO[] {
    const assignedTeamIds = new Set((person.assignedDuty ?? []).map((d) => d.id));
    return this.roles.filter(
      (r) => assignedTeamIds.has((r.value as Record<string, unknown>)?.['teamInstanceId'] as string)
    );
  }

  getAssignedCount(role: FieldInstanceDTO): number {
    return this.allParents.filter((row) =>
      (row.person.assignedRole ?? []).some((r) => r.id === role.id)
    ).length;
  }

  isRoleDisabled(person: PersonDTO, role: FieldInstanceDTO): boolean {
    const max = (role.value as Record<string, unknown>)?.['max'] as number | null;
    if (max == null) return false;
    if (this.isRoleAssigned(person, role)) return false;
    return this.getAssignedCount(role) >= max;
  }

  getRoleTooltip(person: PersonDTO, role: FieldInstanceDTO): string {
    if (!this.isRoleDisabled(person, role)) return '';
    const max = (role.value as Record<string, unknown>)?.['max'] as number;
    return `Maximale Anzahl (${max}) erreicht`;
  }

  toggleTeam(row: ParentRow, team: FieldInstanceDTO): void {
    if (!this.parentTeamsDefinitionId) return;
    const isCurrentlyAssigned = this.isAssigned(row.person, team);

    if (isCurrentlyAssigned) {
      const rolesInTeam = this.roles.filter(
        (r) => (r.value as Record<string, unknown>)?.['teamInstanceId'] === team.id
      );
      const assignedRolesInTeam = rolesInTeam.filter((r) => this.isRoleAssigned(row.person, r));

      if (assignedRolesInTeam.length > 0) {
        const roleNames = assignedRolesInTeam
          .map((r) => (r.value as Record<string, unknown>)?.['label'] as string ?? r.id)
          .join(', ');
        const teamLabel = this.getTeamLabel(team);
        const dialogRef = this.dialog.open(ConfirmDialogComponent, {
          data: {
            message: `${row.name} hat im Team "${teamLabel}" folgende Rollen zugewiesen: ${roleNames}. Team abwählen entfernt diese Rollen. Fortfahren?`,
          },
        });
        dialogRef.afterClosed().subscribe((confirmed: boolean) => {
          if (confirmed) {
            this.doToggleTeam(row, team, isCurrentlyAssigned, assignedRolesInTeam);
          }
        });
        return;
      }
    }

    this.doToggleTeam(row, team, isCurrentlyAssigned, []);
  }

  private doToggleTeam(
    row: ParentRow,
    team: FieldInstanceDTO,
    wasAssigned: boolean,
    rolesToRemove: FieldInstanceDTO[],
  ): void {
    this.personService.assignTeam(row.person.id!, this.parentTeamsDefinitionId!, team.id!).subscribe(() => {
      if (wasAssigned) {
        row.person.assignedDuty = (row.person.assignedDuty ?? []).filter((d) => d.id !== team.id);
        for (const role of rolesToRemove) {
          this.personService.assignRole(
            row.person.id!, this.parentTeamRolesDefinitionId!, role.id!
          ).subscribe(() => {
            row.person.assignedRole = (row.person.assignedRole ?? []).filter((r) => r.id !== role.id);
          });
        }
      } else {
        row.person.assignedDuty = [...(row.person.assignedDuty ?? []), team];
      }
      this.applyFilter();
    });
  }

  toggleRole(row: ParentRow, role: FieldInstanceDTO): void {
    if (!this.parentTeamRolesDefinitionId) return;
    if (this.isRoleDisabled(row.person, role)) return;
    this.personService.assignRole(row.person.id!, this.parentTeamRolesDefinitionId, role.id!).subscribe(() => {
      if (this.isRoleAssigned(row.person, role)) {
        row.person.assignedRole = (row.person.assignedRole ?? []).filter((r) => r.id !== role.id);
      } else {
        row.person.assignedRole = [...(row.person.assignedRole ?? []), role];
      }
    });
  }

  onFilterChange(): void {
    this.applyFilter();
  }

  private applyFilter(): void {
    if (!this.filterTeamId) {
      this.displayedParents = [...this.allParents];
    } else {
      this.displayedParents = this.allParents.filter((row) =>
        (row.person.assignedDuty ?? []).some((d) => d.id === this.filterTeamId)
      );
    }
  }

  getTeamLabel(team: FieldInstanceDTO): string {
    return (team.value as Record<string, unknown>)?.['label'] as string ?? team.id ?? '';
  }

  getRoleLabel(role: FieldInstanceDTO): string {
    return (role.value as Record<string, unknown>)?.['label'] as string ?? role.id ?? '';
  }
}
```

- [ ] **Step 3: `elterneinteilung.component.html` vollständig ersetzen**

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

      <ng-container matColumnDef="rollen">
        <th mat-header-cell *matHeaderCellDef>Rollen</th>
        <td mat-cell *matCellDef="let row">
          <mat-chip-set>
            <mat-chip
              *ngFor="let role of getVisibleRoles(row.person)"
              [class.chip-assigned]="isRoleAssigned(row.person, role)"
              [disabled]="isRoleDisabled(row.person, role)"
              [matTooltip]="getRoleTooltip(row.person, role)"
              (click)="toggleRole(row, role)">
              {{ getRoleLabel(role) }}
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

- [ ] **Step 4: Build prüfen**

```bash
cd D:/GIT/kigruapp/frontend && npx ng build --configuration development 2>&1 | grep -E "^.*error" | head -20
```

Erwartetes Ergebnis: keine Fehler.

- [ ] **Step 5: Manuell testen**

1. Einstellungen → Organisation → Elterneinteilung: Team "Garten" anlegen, aufklappen, Rolle "Spielplatz" (Min: 1, Max: 2) anlegen
2. Administration → Elterneinteilung: Spalte "Rollen" erscheint
3. Person "Garten"-Team zuweisen → "Spielplatz"-Chip erscheint in Rollen-Spalte
4. "Spielplatz" anklicken → Chip wird als zugewiesen markiert
5. Zwei verschiedene Personen "Spielplatz" zuweisen → bei dritter Person ist Chip ausgegraut mit Tooltip "Maximale Anzahl (2) erreicht"
6. Person "Garten"-Team abwählen, wenn "Spielplatz" zugewiesen → Dialog erscheint mit Bestätigung
7. Dialog: "Nein" → Team bleibt, Rolle bleibt
8. Dialog: "Ja" → Team und Rolle werden entfernt

- [ ] **Step 6: Commit**

```bash
cd D:/GIT/kigruapp && git add \
  frontend/src/app/shared/components/confirm-dialog/confirm-dialog.component.ts \
  frontend/src/app/administration/elterneinteilung/elterneinteilung.component.ts \
  frontend/src/app/administration/elterneinteilung/elterneinteilung.component.html
git commit -m "feat: add Rollen column to ElterneinteilungComponent with max-limit, tooltip and team-deselect confirmation"
```
