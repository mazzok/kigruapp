import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FieldDefinition } from '../shared/models/field-definition.model';
import { CookingDutyDTO } from '../shared/models/organisation.model';
import { PersonDTO } from '../shared/models/person.model';

export interface CookingDutyDialogData {
  groups: FieldDefinition[];
  foodProperties: FieldDefinition[];
  familyParents: PersonDTO[];
  currentUserId: string;
  existingDuty?: CookingDutyDTO;
  canEdit: boolean;
}

export interface CookingDutyDialogResult {
  action: 'save' | 'delete';
  date: string;
  groups: string[];
  personId: string;
  description: string;
  foodProperties: Record<string, boolean>;
}

@Component({
  selector: 'app-cooking-duty-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatFormFieldModule, MatInputModule,
    MatDatepickerModule, MatCheckboxModule,
    MatSelectModule, MatButtonModule, MatIconModule,
  ],
  templateUrl: './cooking-duty-dialog.component.html',
  styleUrl: './cooking-duty-dialog.component.scss',
})
export class CookingDutyDialogComponent implements OnInit {
  form!: FormGroup;
  isEdit: boolean;
  canEdit: boolean;

  constructor(
    private dialogRef: MatDialogRef<CookingDutyDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: CookingDutyDialogData,
  ) {
    this.isEdit = !!data.existingDuty;
    this.canEdit = data.canEdit;
  }

  ngOnInit(): void {
    const duty = this.data.existingDuty;

    this.form = new FormGroup({
      date: new FormControl(duty ? new Date(duty.date) : null, Validators.required),
      person: new FormControl(
        duty ? duty.personId : this.data.currentUserId,
        Validators.required,
      ),
      description: new FormControl(duty?.description ?? ''),
    });

    // Add group checkboxes
    for (const group of this.data.groups) {
      const isChecked = duty ? duty.groups.includes(group.id!) : false;
      this.form.addControl('group_' + group.id, new FormControl(isChecked));
    }

    // Add food property checkboxes
    for (const fp of this.data.foodProperties) {
      const isChecked = duty ? duty.foodProperties[fp.id!] === true : false;
      this.form.addControl('food_' + fp.id, new FormControl(isChecked));
    }

    if (!this.canEdit) {
      this.form.disable();
    }
  }

  getParentName(parent: PersonDTO): string {
    const lastName = this.getFieldValue(parent, 'lastName');
    const firstName = this.getFieldValue(parent, 'firstName');
    return `${lastName} ${firstName}`.trim();
  }

  private getFieldValue(person: PersonDTO, fieldName: string): string {
    const field = person.basicProperties?.find((f) => f.fieldName === fieldName);
    return (field?.value as string) ?? '';
  }

  hasSelectedGroups(): boolean {
    return this.data.groups.some((g) => this.form.get('group_' + g.id)?.value);
  }

  save(): void {
    if (!this.form.valid || !this.hasSelectedGroups()) return;

    const dateValue: Date = this.form.value.date;
    const dateStr = dateValue.toISOString().split('T')[0];

    const selectedGroups = this.data.groups
      .filter((g) => this.form.get('group_' + g.id)?.value)
      .map((g) => g.id!);

    const foodProps: Record<string, boolean> = {};
    for (const fp of this.data.foodProperties) {
      if (this.form.get('food_' + fp.id)?.value) {
        foodProps[fp.id!] = true;
      }
    }

    this.dialogRef.close({
      action: 'save',
      date: dateStr,
      groups: selectedGroups,
      personId: this.form.value.person,
      description: this.form.value.description,
      foodProperties: foodProps,
    } as CookingDutyDialogResult);
  }

  deleteDuty(): void {
    this.dialogRef.close({ action: 'delete' } as CookingDutyDialogResult);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
