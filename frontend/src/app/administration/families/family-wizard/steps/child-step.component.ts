import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { FieldDefinitionService } from '../../../../settings/custom-fields/services/field-definition.service';
import { FieldDefinition } from '../../../../shared/models/field-definition.model';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatDatepickerModule, MatNativeDateModule,
  ],
  templateUrl: './child-step.component.html',
})
export class ChildStepComponent implements OnInit {
  form = new FormGroup({
    firstName: new FormControl('', Validators.required),
    lastName: new FormControl('', Validators.required),
    dateOfBirth: new FormControl<Date | null>(null, Validators.required),
    gender: new FormControl('', Validators.required),
    entryDate: new FormControl<Date | null>(null),
    notes: new FormControl(''),
  });

  customFieldDefs: FieldDefinition[] = [];
  customFieldControls: Record<string, FormControl> = {};

  genderOptions = [
    { value: 'male', label: 'männlich' },
    { value: 'female', label: 'weiblich' },
    { value: 'diverse', label: 'divers' },
  ];

  constructor(private fieldDefService: FieldDefinitionService) {}

  ngOnInit(): void {
    this.fieldDefService.list().subscribe((defs) => {
      this.customFieldDefs = defs.filter((d) => d.entity === 'CHILD');
      for (const def of this.customFieldDefs) {
        const control = new FormControl('', def.required ? Validators.required : []);
        this.customFieldControls[def.fieldName] = control;
        (this.form as FormGroup<any>).addControl(`custom_${def.fieldName}`, control);
      }
    });
  }

  get isValid(): boolean {
    return this.form.valid;
  }

  getChildData(): Record<string, unknown> {
    const val = this.form.value;
    const customFields: Record<string, unknown> = {};
    for (const def of this.customFieldDefs) {
      customFields[def.fieldName] = this.customFieldControls[def.fieldName].value;
    }
    return {
      firstName: val.firstName,
      lastName: val.lastName,
      dateOfBirth: val.dateOfBirth?.toISOString().split('T')[0],
      gender: val.gender,
      entryDate: val.entryDate?.toISOString().split('T')[0] ?? null,
      notes: val.notes,
      customFields,
    };
  }
}
