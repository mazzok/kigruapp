import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';
import { buildMonths, dayBackground, CalendarDay, CalendarMonth } from './closure-calendar.util';

@Component({
  selector: 'app-closure-calendar',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  templateUrl: './closure-calendar.component.html',
  styleUrl: './closure-calendar.component.scss',
})
export class ClosureCalendarComponent implements OnChanges {
  /** ISO yyyy-MM-dd, Semesterbeginn */
  @Input() from = '';
  /** ISO yyyy-MM-dd, Semesterende */
  @Input() to = '';
  @Input() periods: ClosurePeriod[] = [];
  @Input() definitions: ClosureDefinition[] = [];
  @Input() holidays: Holiday[] = [];
  /** Elternansicht: keine Auswahl, keine Handler. */
  @Input() readonly = false;

  @Output() selectionChange = new EventEmitter<string[]>();

  readonly weekdayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  months: CalendarMonth[] = [];

  ngOnChanges(): void {
    this.months = buildMonths(this.from, this.to, this.periods, this.definitions, this.holidays);
  }

  background(day: CalendarDay): string {
    return dayBackground(day);
  }

  tooltip(day: CalendarDay): string {
    const parts = [...day.labels];
    if (day.holidayName) {
      parts.unshift(day.holidayName);
    }
    return parts.join(' · ');
  }

  blanks(month: CalendarMonth): number[] {
    return Array.from({ length: month.leadingBlanks }, (_, index) => index);
  }
}
