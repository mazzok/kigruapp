import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingReminderSettingsService } from './cooking-reminder-settings.service';
import { ApiService } from '../../core/services/api.service';

describe('CookingReminderSettingsService', () => {
  let service: CookingReminderSettingsService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get']);
    TestBed.configureTestingModule({
      providers: [CookingReminderSettingsService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingReminderSettingsService);
  });

  it('liest die Status von /cooking-reminder-settings', () => {
    api.get.and.returnValue(of({ active: false }));

    service.get().subscribe();

    expect(api.get).toHaveBeenCalledWith('/cooking-reminder-settings');
  });
});
