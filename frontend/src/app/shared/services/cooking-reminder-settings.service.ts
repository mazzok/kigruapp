import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingReminderStatus } from '../models/cooking-reminder-settings.model';

@Injectable({ providedIn: 'root' })
export class CookingReminderSettingsService {
  constructor(private api: ApiService) {}

  get(): Observable<CookingReminderStatus> {
    return this.api.get<CookingReminderStatus>('/cooking-reminder-settings');
  }
}
