import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import {
  MailSettings,
  MailTestResult,
  UpdateMailSettingsRequest,
} from '../models/mail-settings.model';

@Injectable({ providedIn: 'root' })
export class MailSettingsService {
  constructor(private api: ApiService) {}

  get(): Observable<MailSettings> {
    return this.api.get<MailSettings>('/mail-settings');
  }

  update(request: UpdateMailSettingsRequest): Observable<MailSettings> {
    return this.api.put<MailSettings>('/mail-settings', request);
  }

  test(recipient: string): Observable<MailTestResult> {
    return this.api.post<MailTestResult>('/mail-settings/test', { recipient });
  }
}
