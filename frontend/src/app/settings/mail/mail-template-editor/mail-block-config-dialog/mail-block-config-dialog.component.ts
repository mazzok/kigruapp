import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatTabsModule } from '@angular/material/tabs';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { FieldInstanceDTO } from '../../../../shared/models/field-instance.model';
import { CookingDutyBlockConfig, MailBlockConfig } from '../../../../shared/models/mail-block.model';
import { MailTemplateService } from '../../../../shared/services/mail-template.service';
import { CookingDutyBlockConfigComponent } from './cooking-duty-block-config.component';

export interface MailBlockConfigDialogData {
  blockType: string;
  config: MailBlockConfig;
  groups: FieldInstanceDTO[];
}

const PREVIEW_TAB_INDEX = 1;

@Component({
  selector: 'app-mail-block-config-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatDialogModule, MatButtonModule, MatTabsModule,
    CookingDutyBlockConfigComponent,
  ],
  templateUrl: './mail-block-config-dialog.component.html',
})
export class MailBlockConfigDialogComponent {
  form: FormGroup;

  previewHtml: SafeHtml = '';
  previewLoading = false;
  previewError = false;

  /** JSON snapshot of the config the preview was last loaded for; null until the first successful/attempted load. */
  private lastPreviewedConfigJson: string | null = null;

  constructor(
    private dialogRef: MatDialogRef<MailBlockConfigDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: MailBlockConfigDialogData,
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
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

  /** Loads the preview only when switching to the Vorschau tab, and only if the config changed since the last load. */
  onTabChange(index: number): void {
    if (index !== PREVIEW_TAB_INDEX) {
      return;
    }
    if (this.form.invalid) {
      this.previewHtml = '';
      this.previewError = false;
      this.lastPreviewedConfigJson = null;
      return;
    }
    const currentConfigJson = JSON.stringify(this.form.value);
    if (currentConfigJson === this.lastPreviewedConfigJson) {
      this.previewError = false;
      return;
    }
    this.previewLoading = true;
    this.previewError = false;
    this.mailTemplateService.previewBlock(this.form.value as MailBlockConfig).subscribe({
      next: (result) => {
        this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(result.html);
        this.previewLoading = false;
        this.lastPreviewedConfigJson = currentConfigJson;
      },
      error: () => {
        this.previewError = true;
        this.previewLoading = false;
      },
    });
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
