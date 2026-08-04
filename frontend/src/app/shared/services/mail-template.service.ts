import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailBlockConfig } from '../models/mail-block.model';
import { MailTemplate, MailTemplateKind, PlaceholderTile, SaveMailTemplateRequest } from '../models/mail-template.model';

@Injectable({ providedIn: 'root' })
export class MailTemplateService {
  constructor(private api: ApiService) {}

  list(kind?: MailTemplateKind): Observable<MailTemplate[]> {
    const query = kind ? `?kind=${kind}` : '';
    return this.api.get<MailTemplate[]>(`/mail-templates${query}`);
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

  placeholders(kind?: MailTemplateKind): Observable<PlaceholderTile[]> {
    const query = kind ? `?kind=${kind}` : '';
    return this.api.get<PlaceholderTile[]>(`/mail-templates/placeholders${query}`);
  }

  previewBlock(config: MailBlockConfig): Observable<{ html: string }> {
    return this.api.post<{ html: string }>('/mail-templates/blocks/preview', config);
  }
}
