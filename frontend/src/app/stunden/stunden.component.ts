import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MAT_DATE_LOCALE } from '@angular/material/core';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { NotificationService } from '../shared/services/notification.service';
import { HourEntry, RoleOption } from '../shared/models/hour-entry.model';
import {
  parseHhmm, formatMinutes, toIsoDate, parseIsoDate, formatIsoDateDe,
} from '../shared/util/time-format.util';

const KOCHEN_KEY = '__kochen__';

@Component({
  selector: 'app-stunden',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatDatepickerModule,
  ],
  providers: [
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'de-AT' },
  ],
  templateUrl: './stunden.component.html',
  styleUrl: './stunden.component.scss',
})
export class StundenComponent implements OnInit {
  entries: HourEntry[] = [];
  options: RoleOption[] = [];
  /** Zusatzoption, falls ein bearbeiteter Alt-Eintrag eine nicht mehr aktive Rolle hat. */
  extraOption: { key: string; label: string } | null = null;
  selectedId: string | null = null;
  editing = false;

  form = new FormGroup({
    roleKey: new FormControl<string | null>(null, Validators.required),
    date: new FormControl<Date | null>(null, Validators.required),
    time: new FormControl<string>('', [Validators.required, this.timeValidator]),
    comment: new FormControl<string>(''),
  });

  constructor(
    private hourService: HourEntryService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
    this.hourService.roleOptions().subscribe((opts) => (this.options = opts));
  }

  load(): void {
    this.hourService.listMine().subscribe((entries) => (this.entries = entries));
  }

  private timeValidator(control: FormControl): { [k: string]: boolean } | null {
    return parseHhmm(control.value ?? '') === null ? { time: true } : null;
  }

  roleKey(opt: RoleOption): string {
    return opt.fieldInstanceId ?? KOCHEN_KEY;
  }

  shorthand(entry: HourEntry): string {
    return `${formatIsoDateDe(entry.date)} – ${entry.roleLabel}`;
  }

  formatMinutes = formatMinutes;

  newEntry(): void {
    this.selectedId = null;
    this.extraOption = null;
    this.editing = true;
    this.form.reset({ roleKey: null, date: null, time: '', comment: '' });
  }

  selectForEdit(entry: HourEntry): void {
    this.selectedId = entry.id;
    this.editing = true;
    const key = entry.roleFieldInstanceId ?? KOCHEN_KEY;
    // Alt-Eintrag mit nicht mehr aktiver Rolle: Option temporär bereitstellen.
    const known = this.options.some((o) => this.roleKey(o) === key);
    this.extraOption = known ? null : { key, label: entry.roleLabel };
    this.form.reset({
      roleKey: key,
      date: parseIsoDate(entry.date),
      time: formatMinutes(entry.minutes),
      comment: entry.comment ?? '',
    });
  }

  closeEditor(): void {
    this.selectedId = null;
    this.extraOption = null;
    this.editing = false;
    this.form.reset({ roleKey: null, date: null, time: '', comment: '' });
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
    const request = {
      roleFieldInstanceId: roleKey === KOCHEN_KEY ? null : (roleKey ?? null),
      date: toIsoDate(date),
      minutes,
      comment: this.form.value.comment ?? '',
    };
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.hourService.update(this.selectedId, request)
      : this.hourService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Eintrag aktualisiert' : 'Eintrag gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  delete(entry: HourEntry): void {
    this.hourService.delete(entry.id).subscribe({
      next: () => {
        this.notify.success('Eintrag gelöscht');
        if (this.selectedId === entry.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
