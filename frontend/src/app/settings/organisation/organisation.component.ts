import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
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
    MatDialogModule, IconPickerDialogComponent,
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
  parentTeamsDataSource = new MatTableDataSource<FieldInstanceDTO>();
  parentTeamsColumns = ['label', 'actions'];
  parentTeamsForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
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
}
