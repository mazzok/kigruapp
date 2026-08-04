import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';

import { ClosureCalendarComponent } from '../shared/components/closure-calendar/closure-calendar.component';
import { ClosureDefinitionService } from '../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../shared/services/closure-period.service';
import { HolidayService } from '../shared/services/holiday.service';
import { SemesterService } from '../shared/services/semester.service';
import { ClosureDefinition, ClosurePeriod, Holiday } from '../shared/models/closure.model';

@Component({
  selector: 'app-schliesstage-view',
  standalone: true,
  imports: [CommonModule, ClosureCalendarComponent],
  templateUrl: './schliesstage-view.component.html',
  styleUrl: './schliesstage-view.component.scss',
})
export class SchliesstageViewComponent implements OnInit {
  readonly readonly = true;

  from = '';
  to = '';
  semesterLabel = '';
  definitions: ClosureDefinition[] = [];
  periods: ClosurePeriod[] = [];
  holidays: Holiday[] = [];
  loaded = false;

  constructor(
    private definitionService: ClosureDefinitionService,
    private periodService: ClosurePeriodService,
    private holidayService: HolidayService,
    private semesterService: SemesterService,
  ) {}

  ngOnInit(): void {
    this.semesterService.getAll().subscribe(semesters => {
      const today = this.todayIso();
      // Bewusst datumsbasiert statt semesters[0]: ein noch nicht begonnenes
      // Semester soll Eltern nicht als laufend angezeigt werden.
      const current = semesters.find(
        semester => semester.start.slice(0, 10) <= today && today <= semester.end.slice(0, 10));

      if (!current) {
        this.loaded = true;
        return;
      }

      this.from = current.start.slice(0, 10);
      this.to = current.end.slice(0, 10);
      this.semesterLabel = `${this.from.slice(0, 4)}/${this.to.slice(0, 4)}`;

      forkJoin({
        definitions: this.definitionService.getAll(true),
        periods: this.periodService.getRange(this.from, this.to),
        holidays: this.holidayService.getRange(this.from, this.to),
      }).subscribe({
        next: result => {
          this.definitions = result.definitions;
          this.periods = result.periods;
          this.holidays = result.holidays;
          this.loaded = true;
        },
        error: () => {
          this.loaded = true;
        },
      });
    });
  }

  /** Nur Definitionen, die im geladenen Zeitraum vorkommen — auch deaktivierte. */
  get legend(): ClosureDefinition[] {
    const used = new Set(this.periods.map(period => period.definitionId));
    return this.definitions.filter(definition => used.has(definition.id));
  }

  private todayIso(): string {
    const now = new Date();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${now.getFullYear()}-${month}-${day}`;
  }
}
