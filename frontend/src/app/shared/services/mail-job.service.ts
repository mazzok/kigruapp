import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailJob, SaveMailJobRequest } from '../models/mail-job.model';

@Injectable({ providedIn: 'root' })
export class MailJobService {
  constructor(private api: ApiService) {}

  list(): Observable<MailJob[]> {
    return this.api.get<MailJob[]>('/mail-jobs');
  }

  get(id: string): Observable<MailJob> {
    return this.api.get<MailJob>(`/mail-jobs/${id}`);
  }

  create(request: SaveMailJobRequest): Observable<MailJob> {
    return this.api.post<MailJob>('/mail-jobs', request);
  }

  update(id: string, request: SaveMailJobRequest): Observable<MailJob> {
    return this.api.put<MailJob>(`/mail-jobs/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/mail-jobs/${id}`);
  }

  activate(id: string): Observable<MailJob> {
    return this.api.post<MailJob>(`/mail-jobs/${id}/activate`, undefined);
  }

  deactivate(id: string): Observable<MailJob> {
    return this.api.post<MailJob>(`/mail-jobs/${id}/deactivate`, undefined);
  }
}
