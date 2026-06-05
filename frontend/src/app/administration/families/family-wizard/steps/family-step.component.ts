import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { FamilyService } from '../../services/family.service';
import { Family } from '../../../../shared/models/family.model';

@Component({
  selector: 'app-family-step',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatRadioModule, MatSelectModule, MatFormFieldModule],
  templateUrl: './family-step.component.html',
})
export class FamilyStepComponent implements OnInit {
  form = new FormGroup({
    mode: new FormControl<'new' | 'existing'>('new', Validators.required),
    existingFamilyId: new FormControl<string>(''),
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
    return true;
  }

  get isNewFamily(): boolean {
    return this.form.value.mode === 'new';
  }

  get selectedFamilyId(): string | null {
    return this.form.value.mode === 'existing' ? this.form.value.existingFamilyId ?? null : null;
  }
}
