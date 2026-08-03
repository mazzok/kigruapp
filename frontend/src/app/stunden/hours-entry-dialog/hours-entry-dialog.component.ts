import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DATE_LOCALE, provideNativeDateAdapter } from '@angular/material/core';
import { HourEntry, RoleOption, SaveHourEntryRequest } from '../../shared/models/hour-entry.model';
import { formatMinutes, parseHhmm, parseIsoDate, toIsoDate } from '../../shared/util/time-format.util';

export const KOCHEN_KEY = '__kochen__';

export interface HoursEntryDialogData {
  entry: HourEntry | null;
  options: RoleOption[];
}

@Component({
  selector: 'app-hours-entry-dialog',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatButtonModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './hours-entry-dialog.component.html',
})
export class HoursEntryDialogComponent {
  /** Zusatzoption, falls ein bearbeiteter Alt-Eintrag eine nicht mehr aktive Rolle hat. */
  extraOption: { key: string; label: string } | null = null;

  form = new FormGroup({
    roleKey: new FormControl<string | null>(null, Validators.required),
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, HoursEntryDialogComponent.timeValidator]),
    comment: new FormControl<string>(''),
  });

  constructor(
    private dialogRef: MatDialogRef<HoursEntryDialogComponent, SaveHourEntryRequest>,
    @Inject(MAT_DIALOG_DATA) public data: HoursEntryDialogData,
  ) {
    const entry = data.entry;
    if (!entry) {
      return;
    }
    const key = entry.roleFieldInstanceId ?? KOCHEN_KEY;
    const known = data.options.some((o) => this.roleKey(o) === key);
    this.extraOption = known ? null : { key, label: entry.roleLabel };
    this.form.reset({
      roleKey: key,
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  private static timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  roleKey(option: RoleOption): string {
    return option.fieldInstanceId ?? KOCHEN_KEY;
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const minutes = parseHhmm(this.form.value.time ?? '');
    const date = this.form.value.date;
    if (minutes === null || !date) {
      return;
    }
    const roleKey = this.form.value.roleKey;
    this.dialogRef.close({
      roleFieldInstanceId: roleKey === KOCHEN_KEY ? null : (roleKey ?? null),
      date: toIsoDate(date),
      minutes,
      comment: this.form.value.comment ?? '',
    });
  }
}
