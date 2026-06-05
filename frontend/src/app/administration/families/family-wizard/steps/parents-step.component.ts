import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-parents-step',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule,
    MatCheckboxModule, MatIconModule,
  ],
  templateUrl: './parents-step.component.html',
})
export class ParentsStepComponent {
  parentsArray = new FormArray<FormGroup>([]);
  reuseAddress: Record<number, boolean> = {};

  constructor() {
    this.addParent();
  }

  addParent(): void {
    const group = new FormGroup({
      firstName: new FormControl('', Validators.required),
      lastName: new FormControl('', Validators.required),
      email: new FormControl(''),
      phone: new FormControl(''),
      street: new FormControl(''),
      zip: new FormControl(''),
      city: new FormControl(''),
    });
    this.parentsArray.push(group);
  }

  removeParent(index: number): void {
    this.parentsArray.removeAt(index);
    delete this.reuseAddress[index];
  }

  onReuseAddress(index: number, checked: boolean): void {
    this.reuseAddress[index] = checked;
    if (checked && index > 0) {
      const first = this.parentsArray.at(0).value;
      const current = this.parentsArray.at(index);
      current.patchValue({
        street: first.street,
        zip: first.zip,
        city: first.city,
      });
    }
  }

  get isValid(): boolean {
    return this.parentsArray.valid && this.parentsArray.length > 0;
  }

  getParentsData(): Record<string, unknown>[] {
    return this.parentsArray.controls.map((group) => {
      const val = group.value;
      return {
        firstName: val.firstName,
        lastName: val.lastName,
        email: val.email || null,
        phone: val.phone || null,
        address: {
          street: val.street || '',
          zip: val.zip || '',
          city: val.city || '',
        },
      };
    });
  }
}
