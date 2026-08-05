import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingReminderJob, SaveCookingReminderJobRequest } from '../models/cooking-reminder-job.model';

@Injectable({ providedIn: 'root' })
export class CookingReminderJobService {
  constructor(private api: ApiService) {}

  list(): Observable<CookingReminderJob[]> {
    return this.api.get<CookingReminderJob[]>('/cooking-reminder-jobs');
  }

  create(request: SaveCookingReminderJobRequest): Observable<CookingReminderJob> {
    return this.api.post<CookingReminderJob>('/cooking-reminder-jobs', request);
  }

  update(id: string, request: SaveCookingReminderJobRequest): Observable<CookingReminderJob> {
    return this.api.put<CookingReminderJob>(`/cooking-reminder-jobs/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/cooking-reminder-jobs/${id}`);
  }
}
