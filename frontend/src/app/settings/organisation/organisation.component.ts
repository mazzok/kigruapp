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
    color: new FormControl('#4285f4', Validators.required),
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
