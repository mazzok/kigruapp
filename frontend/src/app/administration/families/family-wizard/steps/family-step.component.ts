import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FamilyService } from '../../services/family.service';
import { Family, FamilyAddress } from '../../../../shared/models/family.model';

@Component({
  selector: 'app-family-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatInputModule, MatRadioModule, MatSelectModule, MatFormFieldModule,
  ],
  templateUrl: './family-step.component.html',
})
export class FamilyStepComponent implements OnInit {
  form = new FormGroup({
    mode: new FormControl<'new' | 'existing'>('new', Validators.required),
    newFamilyName: new FormControl<string>('', Validators.required),
    existingFamilyId: new FormControl<string>(''),
    street: new FormControl<string>(''),
    zip: new FormControl<string>(''),
    city: new FormControl<string>(''),
  });

  existingFamilies: Family[] = [];

  constructor(private familyService: FamilyService) {}

  ngOnInit(): void {
    this.familyService.list().subscribe((families) => {
      this.existingFamilies = families;
    });
  }

  get isValid(): boolean {
    if (this.form.value.mode === 'existing') {
      return !!this.form.value.existingFamilyId;
    }
    return !!this.form.value.newFamilyName?.trim();
  }

  get newFamilyName(): string {
    return this.form.value.newFamilyName?.trim() ?? '';
  }

  get isNewFamily(): boolean {
    return this.form.value.mode === 'new';
  }

  get address(): FamilyAddress | null {
    const { street, zip, city } = this.form.value;
    if (street?.trim() || zip?.trim() || city?.trim()) {
      return { street: street?.trim() ?? '', zip: zip?.trim() ?? '', city: city?.trim() ?? '' };
    }
    return null;
  }

  get selectedFamilyId(): string | null {
    return this.form.value.mode === 'existing' ? this.form.value.existingFamilyId ?? null : null;
  }
}
