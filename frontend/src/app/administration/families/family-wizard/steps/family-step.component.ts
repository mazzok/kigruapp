import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { Family, FamilyAddress } from '../../../../shared/models/family.model';

@Component({
  selector: 'app-family-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatInputModule, MatFormFieldModule,
  ],
  templateUrl: './family-step.component.html',
})
export class FamilyStepComponent {
  form = new FormGroup({
    newFamilyName: new FormControl<string>('', Validators.required),
    street: new FormControl<string>(''),
    zip: new FormControl<string>(''),
    city: new FormControl<string>(''),
  });

  @Input() set editFamily(family: Family | undefined) {
    if (!family) return;
    this.form.patchValue({
      newFamilyName: family.name,
      street: family.address?.street ?? '',
      zip: family.address?.zip ?? '',
      city: family.address?.city ?? '',
    });
  }

  get isValid(): boolean {
    return !!this.form.value.newFamilyName?.trim();
  }

  get newFamilyName(): string {
    return this.form.value.newFamilyName?.trim() ?? '';
  }

  get address(): FamilyAddress | null {
    const { street, zip, city } = this.form.value;
    if (street?.trim() || zip?.trim() || city?.trim()) {
      return { street: street?.trim() ?? '', zip: zip?.trim() ?? '', city: city?.trim() ?? '' };
    }
    return null;
  }
}
