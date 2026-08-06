import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { PERIOD_AMOUNT_OPTIONS } from '../../../../shared/models/mail-block.model';
import { instanceLabel as fieldInstanceLabel } from '../mail-block.util';

@Component({
  selector: 'app-cooking-duty-block-config',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatFormFieldModule, MatSelectModule],
  templateUrl: './cooking-duty-block-config.component.html',
})
export class CookingDutyBlockConfigComponent {
  @Input({ required: true }) form!: FormGroup;
  @Input({ required: true }) groups: FieldInstanceDTO[] = [];

  readonly periodAmountOptions = PERIOD_AMOUNT_OPTIONS;

  instanceLabel(instance: FieldInstanceDTO): string {
    return fieldInstanceLabel(instance);
  }
}
