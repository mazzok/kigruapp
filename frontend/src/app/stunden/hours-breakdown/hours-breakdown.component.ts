import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { OurHours, OurHoursChild } from '../../shared/models/hour-entry.model';
import { buildRingState, currentYearMonth, RingState } from '../../shared/components/hours-ring/hours-ring.util';
import { MonthSpan, summarizeMonths } from '../hours-breakdown.util';
import { formatMinutes, formatIsoDateDe } from '../../shared/util/time-format.util';

@Component({
  selector: 'app-hours-breakdown',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatTooltipModule],
  templateUrl: './hours-breakdown.component.html',
  styleUrl: './hours-breakdown.component.scss',
})
export class HoursBreakdownComponent {
  @Input() set our(value: OurHours | null) {
    this.data = value;
    this.state = buildRingState(value, currentYearMonth(new Date()));
    this.spans = value ? summarizeMonths(value.months) : [];
  }

  data: OurHours | null = null;
  state: RingState | null = null;
  spans: MonthSpan[] = [];
  expanded = true;

  formatMinutes = formatMinutes;

  get hasSoll(): boolean {
    return !!this.data && this.data.sollMinutes > 0;
  }

  /** Kinder in der Reihenfolge der Aufschlüsselung; Spaltenreihenfolge des Monatsverlaufs. */
  get children(): OurHoursChild[] {
    return this.data?.children ?? [];
  }

  minutesFor(span: MonthSpan, childId: string): number | null {
    const share = span.shares.find((s) => s.childId === childId);
    return share ? share.minutes : null;
  }

  shareHint(span: MonthSpan, childId: string): string {
    const share = span.shares.find((s) => s.childId === childId);
    if (!share) return '';
    const parts: string[] = [];
    if (share.fractionPercent < 100) parts.push(`${share.fractionPercent} % anteilig`);
    if (share.discountPercent > 0) parts.push(`−${share.discountPercent} %`);
    return parts.join(', ');
  }

  /** "ab 16.10.2026" / "bis 31.01.2027" / "" bei ganzem Semester. */
  periodHint(child: OurHoursChild): string {
    const parts: string[] = [];
    if (child.entryDate) parts.push(`ab ${formatIsoDateDe(child.entryDate)}`);
    if (child.exitDate) parts.push(`bis ${formatIsoDateDe(child.exitDate)}`);
    return parts.join(', ');
  }
}
