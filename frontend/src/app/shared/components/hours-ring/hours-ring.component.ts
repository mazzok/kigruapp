import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { HoursSummaryService } from '../../services/hours-summary.service';
import { formatMinutes } from '../../util/time-format.util';
import { RingState, buildRingState, currentYearMonth } from './hours-ring.util';

@Component({
  selector: 'app-hours-ring',
  standalone: true,
  imports: [CommonModule, RouterModule, MatTooltipModule],
  templateUrl: './hours-ring.component.html',
  styleUrl: './hours-ring.component.scss',
})
export class HoursRingComponent implements OnInit, OnDestroy {
  state: RingState | null = null;

  /** Passt in die viewBox 40×40 bei Strichbreite 4. */
  readonly radius = 16;
  readonly circumference = 2 * Math.PI * 16;

  private sub?: Subscription;

  constructor(private summary: HoursSummaryService) {}

  ngOnInit(): void {
    this.sub = this.summary.summary$.subscribe((our) => {
      this.state = buildRingState(our, currentYearMonth(new Date()));
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  /** Ungezeichneter Rest des Kreises: 0 = voll. */
  get dashOffset(): number {
    const percent = this.state?.ringPercent ?? 0;
    return this.circumference * (1 - percent / 100);
  }

  formatMinutes = formatMinutes;
}
