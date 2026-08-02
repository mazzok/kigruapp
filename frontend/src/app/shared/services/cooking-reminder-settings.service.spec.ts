import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingReminderSettingsService } from './cooking-reminder-settings.service';
import { ApiService } from '../../core/services/api.service';

describe('CookingReminderSettingsService', () => {
  let service: CookingReminderSettingsService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'put']);
    TestBed.configureTestingModule({
      providers: [CookingReminderSettingsService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingReminderSettingsService);
  });

  it('liest die Einstellungen von /cooking-reminder-settings', () => {
    api.get.and.returnValue(of({ senderAccountId: null, templateId: null, subject: null, sendTime: '07:00', active: false }));

    service.get().subscribe();

    expect(api.get).toHaveBeenCalledWith('/cooking-reminder-settings');
  });

  it('speichert die Einstellungen per PUT', () => {
    const settings = { senderAccountId: 'a1', templateId: 't1', subject: 'Betreff', sendTime: '18:30', active: true };
    api.put.and.returnValue(of(settings));

    service.save(settings).subscribe();

    expect(api.put).toHaveBeenCalledWith('/cooking-reminder-settings', settings);
  });
});
