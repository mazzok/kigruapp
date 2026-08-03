import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter, MAT_DATE_LOCALE } from '@angular/material/core';
import { HourEntryService } from '../../shared/services/hour-entry.service';
import { SemesterService } from '../../shared/services/semester.service';
import { NotificationService } from '../../shared/services/notification.service';
import { FamilyHoursSummary, HourEntry } from '../../shared/models/hour-entry.model';
import {
  parseHhmm, formatMinutes, formatIsoDateDe, parseIsoDate, toIsoDate,
} from '../../shared/util/time-format.util';

@Component({
  selector: 'app-stundenuebersicht',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './stundenuebersicht.component.html',
  styleUrl: './stundenuebersicht.component.scss',
})
export class StundenuebersichtComponent implements OnInit {
  semesters: { id: string; start: string; end: string }[] = [];
  selectedSemesterId: string | null = null;
  families: FamilyHoursSummary[] = [];
  expandedFamilyId: string | null = null;
  editingEntryId: string | null = null;

  editForm = new FormGroup({
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, this.timeValidator]),
    comment: new FormControl<string>(''),
  });

  formatMinutes = formatMinutes;
  formatIsoDateDe = formatIsoDateDe;

  constructor(
    private hourService: HourEntryService,
    private semesterService: SemesterService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe({
      next: (semesters) => {
        this.semesters = semesters as any;
        this.selectedSemesterId = this.semesters[0]?.id ?? null;
        this.loadFamilies();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  private timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  loadFamilies(): void {
    if (!this.selectedSemesterId) {
      this.families = [];
      return;
    }
    this.hourService.familySummary(this.selectedSemesterId).subscribe({
      next: (f) => (this.families = f),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  onSemesterChange(): void {
    this.editingEntryId = null;
    this.loadFamilies();
  }

  toggleFamily(familyId: string): void {
    this.expandedFamilyId = this.expandedFamilyId === familyId ? null : familyId;
  }

  isNegative(f: FamilyHoursSummary): boolean {
    return f.istMinutes < f.sollMinutes;
  }

  balanceTooltip(f: FamilyHoursSummary): string {
    // familyMonthlyMinutes ist 0, wenn kein Monat alle Kinder voll enthält
    // (unterjähriger Ein-/Austritt) — dann bleibt der Satzteil weg.
    const monthly = f.familyMonthlyMinutes > 0
      ? ` · ${formatMinutes(f.familyMonthlyMinutes)}/Monat × ${f.monthsInSemester} Monate`
      : '';
    return `${f.childCount} Kinder${monthly} ` +
      `= ${formatMinutes(f.sollMinutes)} Soll; Ist ${formatMinutes(f.istMinutes)}`;
  }

  startEdit(entry: HourEntry): void {
    this.editingEntryId = entry.id;
    this.editForm.reset({
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  cancelEdit(): void {
    this.editingEntryId = null;
  }

  saveEdit(entry: HourEntry): void {
    if (this.editForm.invalid) {
      this.editForm.markAllAsTouched();
      return;
    }
    const minutes = parseHhmm(this.editForm.value.time ?? '');
    const date = this.editForm.value.date;
    if (minutes === null || !date) {
      return;
    }
    const request = {
      roleFieldInstanceId: entry.roleFieldInstanceId, // Rolle unverändert
      date: toIsoDate(date),
      minutes,
      comment: this.editForm.value.comment ?? '',
    };
    this.hourService.update(entry.id, request).subscribe({
      next: () => {
        this.notify.success('Eintrag aktualisiert');
        this.editingEntryId = null;
        this.loadFamilies();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(entry: HourEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        this.loadFamilies();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
