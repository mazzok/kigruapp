import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ApplyPeriodsRequest, ClosurePeriod } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class ClosurePeriodService {
  constructor(private api: ApiService) {}

  getRange(from: string, to: string): Observable<ClosurePeriod[]> {
    return this.api.get<ClosurePeriod[]>(`/closure-periods?from=${from}&to=${to}`);
  }

  /** Sendet die rohe Tagesauswahl; Split und Merge passieren im Backend. */
  apply(request: ApplyPeriodsRequest): Observable<ClosurePeriod[]> {
    return this.api.post<ClosurePeriod[]>('/closure-periods/apply', request);
  }
}
