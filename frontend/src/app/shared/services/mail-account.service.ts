import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { MailAccount } from '../models/mail-account.model';

@Injectable({ providedIn: 'root' })
export class MailAccountService {
  constructor(private api: ApiService) {}

  list(): Observable<MailAccount[]> {
    return this.api.get<MailAccount[]>('/mail-accounts');
  }
}
