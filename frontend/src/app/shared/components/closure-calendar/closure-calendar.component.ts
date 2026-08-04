import {
  Component, EventEmitter, HostListener, Input, OnChanges, OnInit, Output,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';

import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';
import {
  buildMonths, dayBackground, selectableRange, CalendarDay, CalendarMonth,
} from './closure-calendar.util';

@Component({
  selector: 'app-closure-calendar',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  templateUrl: './closure-calendar.component.html',
  styleUrl: './closure-calendar.component.scss',
})
export class ClosureCalendarComponent implements OnChanges, OnInit {
  /** ISO yyyy-MM-dd, Semesterbeginn */
  @Input() from = '';
  /** ISO yyyy-MM-dd, Semesterende */
  @Input() to = '';
  @Input() periods: ClosurePeriod[] = [];
  @Input() definitions: ClosureDefinition[] = [];
  @Input() holidays: Holiday[] = [];
  /** Elternansicht: keine Auswahl, keine Handler. */
  @Input() readonly = false;
  /** 'stacked' (Default, Elternansicht): Monate untereinander. 'row': Monate horizontal scrollbar (Admin-Maske). */
  @Input() layout: 'stacked' | 'row' = 'stacked';

  @Output() selectionChange = new EventEmitter<string[]>();

  readonly weekdayLabels = ['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So'];
  months: CalendarMonth[] = [];

  /** Aufsteigend sortierte ISO-Tage der aktuellen Auswahl. */
  selectedDays: string[] = [];
  ctrlActive = false;

  private selected = new Set<string>();
  private base = new Set<string>();
  private anchor: string | null = null;
  private dragging = false;
  private removing = false;
  private pointerInside = false;

  ngOnChanges(): void {
    this.rebuild();
  }

  // Ohne Template-Bindings — etwa im Test — feuert ngOnChanges nie. Der erste
  // Change-Detection-Lauf muss das Raster trotzdem aufbauen.
  ngOnInit(): void {
    this.rebuild();
  }

  private rebuild(): void {
    this.months = buildMonths(this.from, this.to, this.periods, this.definitions, this.holidays);
    this.clearSelection();
  }

  isSelected(day: CalendarDay): boolean {
    return this.selected.has(day.date);
  }

  onDayMouseDown(day: CalendarDay, event: MouseEvent): void {
    if (this.readonly || !day.selectable) {
      return;
    }
    event.preventDefault();
    const additive = event.ctrlKey || event.metaKey;
    // Mit STRG bleibt Bestehendes erhalten; ohne STRG ersetzt die Ziehung alles.
    this.base = new Set(additive ? this.selected : []);
    // Beginnt die Ziehung mit STRG auf einem markierten Tag, nimmt sie weg.
    this.removing = additive && this.selected.has(day.date);
    this.anchor = day.date;
    this.dragging = true;
    this.applyRange(day.date);
  }

  onDayMouseEnter(day: CalendarDay): void {
    if (this.dragging) {
      this.applyRange(day.date);
    }
  }

  @HostListener('document:mouseup')
  onDocumentMouseUp(): void {
    if (!this.dragging) {
      return;
    }
    this.dragging = false;
    this.anchor = null;
    this.selectionChange.emit(this.selectedDays);
  }

  onPointerEnter(): void {
    this.pointerInside = true;
  }

  onPointerLeave(): void {
    this.pointerInside = false;
    this.ctrlActive = false;
  }

  // STRG wirkt ausschliesslich innerhalb des Kalenders.
  @HostListener('document:keydown', ['$event'])
  @HostListener('document:keyup', ['$event'])
  onCtrlState(event: KeyboardEvent): void {
    this.ctrlActive = !this.readonly && this.pointerInside && (event.ctrlKey || event.metaKey);
  }

  clearSelection(): void {
    this.selected = new Set();
    this.base = new Set();
    this.selectedDays = [];
  }

  private applyRange(end: string): void {
    if (this.anchor === null) {
      return;
    }
    const range = selectableRange(this.months, this.anchor, end);
    const next = new Set(this.base);
    for (const date of range) {
      if (this.removing) {
        next.delete(date);
      } else {
        next.add(date);
      }
    }
    this.selected = next;
    this.selectedDays = [...next].sort();
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
