import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin, Observable } from 'rxjs';

import { ClosureCalendarComponent } from '../../shared/components/closure-calendar/closure-calendar.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ClosureReviseDialogComponent } from './closure-revise-dialog.component';
import {
  ClosureDefinition, ClosureDefinitionRequest, ClosurePeriod, Holiday,
} from '../../shared/models/closure.model';
import { Semester } from '../../shared/models/semester.model';

export type TriState = 'all' | 'none' | 'some';

@Component({
  selector: 'app-schliesstage',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule, MatFormFieldModule, MatSelectModule, MatCheckboxModule,
    MatProgressBarModule, MatButtonModule, MatIconModule, MatInputModule, MatTableModule,
    ClosureCalendarComponent,
  ],
  templateUrl: './schliesstage.component.html',
  styleUrl: './schliesstage.component.scss',
})
export class SchliesstageComponent implements OnInit {
  @ViewChild(ClosureCalendarComponent) calendar?: ClosureCalendarComponent;

  semesters: Semester[] = [];
  selectedSemesterId: string | null = null;

  /** ISO yyyy-MM-dd, Grenzen des gewaehlten Semesters. */
  from = '';
  to = '';

  definitions: ClosureDefinition[] = [];
  periods: ClosurePeriod[] = [];
  holidays: Holiday[] = [];
  selectedDays: string[] = [];
  loading = false;

  constructor(
    private definitionService: ClosureDefinitionService,
    private periodService: ClosurePeriodService,
    private holidayService: HolidayService,
    private semesterService: SemesterService,
    private snackBar: MatSnackBar,
    private dialog: MatDialog,
  ) {}

  readonly definitionColumns = ['color', 'label', 'status', 'actions'];

  definitionForm = new FormGroup({
    label: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    color: new FormControl('#4285f4', { nonNullable: true, validators: [Validators.required] }),
  });

  editForm = new FormGroup({
    label: new FormControl('', { nonNullable: true }),
    color: new FormControl('#4285f4', { nonNullable: true }),
  });

  editingId: string | null = null;
  private editingOriginal: ClosureDefinition | null = null;

  /** Nur aktive Definitionen lassen sich zuweisen; deaktivierte bleiben sichtbar. */
  get assignableDefinitions(): ClosureDefinition[] {
    return this.definitions.filter(definition => definition.active);
  }

  addDefinition(): void {
    const label = this.definitionForm.value.label?.trim() ?? '';
    const color = this.definitionForm.value.color ?? '#4285f4';
    if (!label) {
      return;
    }
    this.definitionService.create({ label, color }).subscribe({
      next: () => {
        this.definitionForm.reset({ label: '', color: '#4285f4' });
        this.reloadDefinitions();
      },
      error: () => this.snackBar.open('Definition konnte nicht angelegt werden', 'OK', { duration: 6000 }),
    });
  }

  startEdit(definition: ClosureDefinition): void {
    this.editingId = definition.id;
    this.editingOriginal = definition;
    this.editForm.setValue({ label: definition.label, color: definition.color });
  }

  cancelEdit(): void {
    this.editingId = null;
    this.editingOriginal = null;
  }

  /**
   * Ob Zeitraeume verknuepft sind, weiss nur das Backend. Statt eines eigenen
   * Zaehl-Endpoints wird PUT versucht; ein 409 loest den Warndialog aus.
   */
  commitEdit(): void {
    const original = this.editingOriginal;
    if (!original) {
      return;
    }
    const request: ClosureDefinitionRequest = {
      label: this.editForm.value.label?.trim() ?? '',
      color: this.editForm.value.color ?? original.color,
    };
    if (!request.label || (request.label === original.label && request.color === original.color)) {
      this.cancelEdit();
      return;
    }

    this.definitionService.update(original.id, request).subscribe({
      next: () => {
        this.cancelEdit();
        this.reloadDefinitions();
      },
      error: (error: { status?: number }) => {
        if (error?.status !== 409) {
          this.snackBar.open('Änderung konnte nicht gespeichert werden', 'OK', { duration: 6000 });
          this.cancelEdit();
          return;
        }
        this.dialog.open(ClosureReviseDialogComponent).afterClosed().subscribe(result => {
          if (result !== 'revise') {
            // Abbrechen: zurueck auf den zuletzt gespeicherten Stand.
            this.cancelEdit();
            return;
          }
          this.definitionService.revise(original.id, request).subscribe({
            next: () => {
              this.cancelEdit();
              this.reloadDefinitions();
            },
            error: () => {
              this.snackBar.open('Kopie konnte nicht angelegt werden', 'OK', { duration: 6000 });
              this.cancelEdit();
            },
          });
        });
      },
    });
  }

  setActive(definition: ClosureDefinition, active: boolean): void {
    // Union aus zwei Observable-Typen — ohne Verbreiterung ist subscribe nicht aufrufbar.
    const request: Observable<unknown> = active
      ? this.definitionService.update(definition.id, {
          label: definition.label, color: definition.color, active: true,
        })
      : this.definitionService.deactivate(definition.id);

    request.subscribe({
      next: () => this.reloadDefinitions(),
      error: () => this.snackBar.open('Status konnte nicht geändert werden', 'OK', { duration: 6000 }),
    });
  }

  ngOnInit(): void {
    this.semesterService.getAll().subscribe(semesters => {
      this.semesters = semesters;
      // Wie in allen anderen Masken: zuletzt angelegtes Semester zuerst.
      this.selectedSemesterId = semesters[0]?.id ?? null;
      this.loadCalendar();
    });
  }

  onSemesterChange(semesterId: string): void {
    this.selectedSemesterId = semesterId;
    this.loadCalendar();
  }

  onSelectionChange(days: string[]): void {
    this.selectedDays = days;
  }

  semesterLabel(semester: Semester): string {
    return `${semester.start.slice(0, 4)}/${semester.end.slice(0, 4)}`;
  }

  /**
   * Gilt die Definition auf allen gewaehlten Tagen, auf einem Teil, oder auf keinem?
   * Der unbestimmte Zustand macht sichtbar, was auf der Auswahl bereits liegt.
   */
  tristate(definitionId: string): TriState {
    if (this.selectedDays.length === 0) {
      return 'none';
    }
    const covered = this.selectedDays.filter(day => this.coversDay(definitionId, day)).length;
    if (covered === 0) {
      return 'none';
    }
    return covered === this.selectedDays.length ? 'all' : 'some';
  }

  /** Anhaken weist zu, Abhaken entfernt — dieselbe Geste fuer beides. */
  toggleDefinition(definition: ClosureDefinition): void {
    if (this.selectedDays.length === 0) {
      return;
    }
    const mode = this.tristate(definition.id) === 'all' ? 'remove' : 'assign';
    this.loading = true;
    this.periodService
      .apply({ days: this.selectedDays, definitionId: definition.id, mode })
      .subscribe({
        next: () => {
          this.calendar?.clearSelection();
          this.selectedDays = [];
          this.reloadPeriods();
        },
        error: (error: { error?: string }) => {
          this.loading = false;
          this.snackBar.open(
            typeof error?.error === 'string' ? error.error : 'Zuordnung fehlgeschlagen',
            'OK', { duration: 6000 });
        },
      });
  }

  /** Von Task 11 aufgerufen, wenn sich die Definitionsliste geaendert hat. */
  reloadDefinitions(): void {
    this.definitionService.getAll(true).subscribe(definitions => {
      this.definitions = definitions;
    });
  }

  private coversDay(definitionId: string, day: string): boolean {
    return this.periods.some(
      period => period.definitionId === definitionId && period.from <= day && day <= period.to);
  }

  private loadCalendar(): void {
    const semester = this.semesters.find(s => s.id === this.selectedSemesterId);
    if (!semester) {
      this.from = '';
      this.to = '';
      this.periods = [];
      this.holidays = [];
      return;
    }
    // Der Instant kommt als ISO-String; der Datumsanteil ist der Kalendertag.
    this.from = semester.start.slice(0, 10);
    this.to = semester.end.slice(0, 10);
    this.selectedDays = [];
    this.loading = true;

    forkJoin({
      definitions: this.definitionService.getAll(true),
      periods: this.periodService.getRange(this.from, this.to),
      holidays: this.holidayService.getRange(this.from, this.to),
    }).subscribe({
      next: result => {
        this.definitions = result.definitions;
        this.periods = result.periods;
        this.holidays = result.holidays;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Schließtage konnten nicht geladen werden', 'OK', { duration: 6000 });
      },
    });
  }

  private reloadPeriods(): void {
    this.periodService.getRange(this.from, this.to).subscribe({
      next: periods => {
        this.periods = periods;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Zeiträume konnten nicht geladen werden', 'OK', { duration: 6000 });
      },
    });
  }
}
