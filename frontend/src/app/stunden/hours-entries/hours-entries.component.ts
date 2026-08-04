import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { OurHoursEntry } from '../../shared/models/hour-entry.model';
import { formatIsoDateDe, formatMinutes } from '../../shared/util/time-format.util';
import { formatMonthLabel } from '../hours-breakdown.util';

@Component({
  selector: 'app-hours-entries',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatSelectModule,
  ],
  templateUrl: './hours-entries.component.html',
  styleUrl: './hours-entries.component.scss',
})
export class HoursEntriesComponent {
  @Input() entries: OurHoursEntry[] = [];
  @Input() ownPersonId: string | null = null;

  @Output() edit = new EventEmitter<OurHoursEntry>();
  @Output() remove = new EventEmitter<OurHoursEntry>();
  @Output() create = new EventEmitter<void>();

  selectedMonth = '';

  formatMinutes = formatMinutes;
  formatIsoDateDe = formatIsoDateDe;
  formatMonthLabel = formatMonthLabel;

  /** Monate der vorhandenen Einträge, absteigend. */
  get months(): string[] {
    const months = new Set<string>();
    for (const entry of this.sorted) {
      if (entry.date) months.add(entry.date.substring(0, 7));
    }
    return [...months];
  }

  get sorted(): OurHoursEntry[] {
    return [...this.entries].sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));
  }

  get visible(): OurHoursEntry[] {
    return this.selectedMonth
      ? this.sorted.filter((e) => (e.date ?? '').startsWith(this.selectedMonth))
      : this.sorted;
  }

  isOwn(entry: OurHoursEntry): boolean {
    return this.ownPersonId !== null && entry.personId === this.ownPersonId;
  }
}
