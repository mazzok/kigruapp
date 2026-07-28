import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailAccount, SaveMailAccountRequest } from '../models/mail-account.model';

@Injectable({ providedIn: 'root' })
export class MailAccountService {
  constructor(private api: ApiService) {}

  list(): Observable<MailAccount[]> {
    return this.api.get<MailAccount[]>('/mail-accounts');
  }

  get(id: string): Observable<MailAccount> {
    return this.api.get<MailAccount>(`/mail-accounts/${id}`);
  }

  create(request: SaveMailAccountRequest): Observable<MailAccount> {
    return this.api.post<MailAccount>('/mail-accounts', request);
  }

  update(id: string, request: SaveMailAccountRequest): Observable<MailAccount> {
    return this.api.put<MailAccount>(`/mail-accounts/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/mail-accounts/${id}`);
  }
}
