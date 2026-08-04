import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { CookingDutyBlockConfigComponent } from './cooking-duty-block-config.component';

export interface MailBlockConfigDialogData {
  blockType: string;
  config: MailBlockConfig;
  groups: FieldInstanceDTO[];
}

@Component({
  selector: 'app-mail-block-config-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, MatDialogModule, MatButtonModule, CookingDutyBlockConfigComponent],
  templateUrl: './mail-block-config-dialog.component.html',
})
export class MailBlockConfigDialogComponent {
  form: FormGroup;

  constructor(
    private dialogRef: MatDialogRef<MailBlockConfigDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: MailBlockConfigDialogData,
  ) {
    this.form = this.buildForm(data.blockType, data.config);
  }

  private buildForm(blockType: string, config: MailBlockConfig): FormGroup {
    if (blockType === 'cookingDuty') {
      const cfg = config as CookingDutyBlockConfig;
      return new FormGroup({
        type: new FormControl<'cookingDuty'>('cookingDuty', { nonNullable: true }),
        groupId: new FormControl(cfg.groupId, Validators.required),
        periodUnit: new FormControl<'week' | 'month'>(cfg.periodUnit, { nonNullable: true, validators: Validators.required }),
        periodAmount: new FormControl(cfg.periodAmount, { nonNullable: true, validators: Validators.required }),
      });
    }
    return new FormGroup({});
  }

  save(): void {
    if (this.form.invalid) {
      return;
    }
    this.dialogRef.close(this.form.value as MailBlockConfig);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
