import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { CookingReminderSettings } from '../models/cooking-reminder-settings.model';

@Injectable({ providedIn: 'root' })
export class CookingReminderSettingsService {
  constructor(private api: ApiService) {}

  get(): Observable<CookingReminderSettings> {
    return this.api.get<CookingReminderSettings>('/cooking-reminder-settings');
  }

  save(settings: CookingReminderSettings): Observable<CookingReminderSettings> {
    return this.api.put<CookingReminderSettings>('/cooking-reminder-settings', settings);
  }
}
