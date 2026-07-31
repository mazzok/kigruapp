import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { forkJoin } from 'rxjs';

import { ClosureCalendarComponent } from '../../shared/components/closure-calendar/closure-calendar.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../../shared/models/closure.model';
import { Semester } from '../../shared/models/semester.model';

export type TriState = 'all' | 'none' | 'some';

@Component({
  selector: 'app-schliesstage',
  standalone: true,
  imports: [
    CommonModule, MatFormFieldModule, MatSelectModule, MatCheckboxModule,
    MatProgressBarModule, ClosureCalendarComponent,
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
  ) {}

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
    this.definitionService.getAll().subscribe(definitions => {
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
      definitions: this.definitionService.getAll(),
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
