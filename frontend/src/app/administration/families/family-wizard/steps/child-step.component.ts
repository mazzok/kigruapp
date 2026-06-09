import { Component, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { CustomFieldsFormComponent } from '../../../../shared/components/custom-fields-form/custom-fields-form.component';

@Component({
  selector: 'app-child-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatDatepickerModule, MatNativeDateModule,
    CustomFieldsFormComponent,
  ],
  templateUrl: './child-step.component.html',
})
export class ChildStepComponent {
  @ViewChild('customFields') customFieldsForm?: CustomFieldsFormComponent;

  form = new FormGroup({
    firstName: new FormControl('', Validators.required),
    lastName: new FormControl('', Validators.required),
    dateOfBirth: new FormControl<Date | null>(null, Validators.required),
    gender: new FormControl('', Validators.required),
    entryDate: new FormControl<Date | null>(null),
    notes: new FormControl(''),
  });

  genderOptions = [
    { value: 'male', label: 'Bub' },
    { value: 'female', label: 'Mädchen' },
  ];

  get isValid(): boolean {
    return this.form.valid && (this.customFieldsForm?.isValid ?? true);
  }

  getChildData(): Record<string, unknown> {
    const val = this.form.value;
    return {
      firstName: val.firstName,
      lastName: val.lastName,
      dateOfBirth: val.dateOfBirth?.toISOString().split('T')[0],
      gender: val.gender,
      entryDate: val.entryDate?.toISOString().split('T')[0] ?? null,
      notes: val.notes,
    };
  }

  saveCustomFields(childId: string) {
    return this.customFieldsForm?.saveInstances(childId);
  }
}
