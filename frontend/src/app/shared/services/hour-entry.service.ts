import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { HourEntry, HourSummary, RoleOption, SaveHourEntryRequest } from '../models/hour-entry.model';

@Injectable({ providedIn: 'root' })
export class HourEntryService {
  constructor(private api: ApiService) {}

  listMine(): Observable<HourEntry[]> {
    return this.api.get<HourEntry[]>('/hour-entries/me');
  }

  roleOptions(semesterId?: string): Observable<RoleOption[]> {
    const q = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.get<RoleOption[]>(`/hour-entries/role-options${q}`);
  }

  create(request: SaveHourEntryRequest): Observable<HourEntry> {
    return this.api.post<HourEntry>('/hour-entries', request);
  }

  update(id: string, request: SaveHourEntryRequest): Observable<HourEntry> {
    return this.api.put<HourEntry>(`/hour-entries/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/hour-entries/${id}`);
  }

  summary(semesterId: string): Observable<HourSummary[]> {
    return this.api.get<HourSummary[]>(`/hour-entries/summary?semesterId=${semesterId}`);
  }
}
