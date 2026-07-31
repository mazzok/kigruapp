import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { HourEntryService } from './hour-entry.service';
import { OurHours } from '../models/hour-entry.model';

/**
 * Hält die Stundenübersicht der eigenen Familie für das jüngste Semester als
 * gemeinsamen Zustand: der Header-Ring und die Seite "Unsere Stunden" lesen
 * dieselbe Quelle, damit der Ring nach dem Erfassen sofort stimmt.
 */
@Injectable({ providedIn: 'root' })
export class HoursSummaryService {
  private subject = new BehaviorSubject<OurHours | null>(null);
  summary$: Observable<OurHours | null> = this.subject.asObservable();

  constructor(private hourService: HourEntryService) {}

  get current(): OurHours | null {
    return this.subject.value;
  }

  /** Lädt neu; Fehler bleiben still, die Anzeige verschwindet dann einfach. */
  reload(): void {
    this.hourService.our('').subscribe({
      next: (summary) => this.subject.next(summary),
      error: () => this.subject.next(null),
    });
  }

  clear(): void {
    this.subject.next(null);
  }
}
