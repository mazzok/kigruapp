import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailTemplate, PlaceholderTile, SaveMailTemplateRequest } from '../models/mail-template.model';

@Injectable({ providedIn: 'root' })
export class MailTemplateService {
  constructor(private api: ApiService) {}

  list(): Observable<MailTemplate[]> {
    return this.api.get<MailTemplate[]>('/mail-templates');
  }

  get(id: string): Observable<MailTemplate> {
    return this.api.get<MailTemplate>(`/mail-templates/${id}`);
  }

  create(request: SaveMailTemplateRequest): Observable<MailTemplate> {
    return this.api.post<MailTemplate>('/mail-templates', request);
  }

  update(id: string, request: SaveMailTemplateRequest): Observable<MailTemplate> {
    return this.api.put<MailTemplate>(`/mail-templates/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/mail-templates/${id}`);
  }

  placeholders(): Observable<PlaceholderTile[]> {
    return this.api.get<PlaceholderTile[]>('/mail-templates/placeholders');
  }
}
