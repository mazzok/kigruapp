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
import { OrganisationService } from '../../shared/services/organisation.service';
import { FieldDefinitionService } from '../custom-fields/services/field-definition.service';
import { OrganisationDTO, DutyEntryDTO } from '../../shared/models/organisation.model';
import { FieldDefinition } from '../../shared/models/field-definition.model';

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
  groupsDataSource = new MatTableDataSource<FieldDefinition>();
  groupColumns = ['label', 'color', 'actions'];
  groupForm = new FormGroup({
    labelDe: new FormControl('', Validators.required),
    color: new FormControl('#4285f4', Validators.required),
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
    private dialog: MatDialog,
  ) {}

  ngOnInit(): void {
    this.loadGroups();
    this.loadDutySettings();
  }

  // --- Groups ---

  loadGroups(): void {
    this.orgService.getByTag('groups').subscribe((org) => {
      this.groupsOrg = org;
      this.groupsDataSource.data = org.definitions;
    });
  }

  addGroup(): void {
    if (!this.groupForm.valid || !this.groupsOrg) return;
    const labelDe = this.groupForm.value.labelDe!;
    const color = this.groupForm.value.color!;

    const newDef: FieldDefinition = {
      fieldName: 'group',
      label: { de: labelDe },
      jsonSchema: { type: 'string' },
      required: false,
      properties: { color },
    };

    this.fieldDefService.create(newDef).subscribe((created) => {
      const updatedIds = [...this.groupsOrg!.definitions.map((d) => d.id!), created.id!];
      this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).subscribe(() => {
        this.groupForm.reset({ color: '#4285f4' });
        this.loadGroups();
      });
    });
  }

  deleteGroup(def: FieldDefinition): void {
    if (!this.groupsOrg) return;
    this.fieldDefService.outdate(def.id!).subscribe(() => {
      const updatedIds = this.groupsOrg!.definitions.filter((d) => d.id !== def.id).map((d) => d.id!);
      this.orgService.update(this.groupsOrg!.id, { definitionIds: updatedIds }).subscribe(() => {
        this.loadGroups();
      });
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
}
