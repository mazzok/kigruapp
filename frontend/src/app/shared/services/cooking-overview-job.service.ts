import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingOverviewJob, SaveCookingOverviewJobRequest } from '../models/cooking-overview-job.model';

@Injectable({ providedIn: 'root' })
export class CookingOverviewJobService {
  constructor(private api: ApiService) {}

  list(): Observable<CookingOverviewJob[]> {
    return this.api.get<CookingOverviewJob[]>('/cooking-overview-jobs');
  }

  create(request: SaveCookingOverviewJobRequest): Observable<CookingOverviewJob> {
    return this.api.post<CookingOverviewJob>('/cooking-overview-jobs', request);
  }

  update(id: string, request: SaveCookingOverviewJobRequest): Observable<CookingOverviewJob> {
    return this.api.put<CookingOverviewJob>(`/cooking-overview-jobs/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/cooking-overview-jobs/${id}`);
  }
}
