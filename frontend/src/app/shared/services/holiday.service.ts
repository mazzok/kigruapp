import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { Holiday } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class HolidayService {
  constructor(private api: ApiService) {}

  getRange(from: string, to: string): Observable<Holiday[]> {
    return this.api.get<Holiday[]>(`/holidays?from=${from}&to=${to}`);
  }
}
