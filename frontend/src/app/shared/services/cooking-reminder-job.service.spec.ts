import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingReminderJobService } from './cooking-reminder-job.service';
import { ApiService } from '../../core/services/api.service';

describe('CookingReminderJobService', () => {
  let service: CookingReminderJobService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'post', 'put', 'delete']);
    TestBed.configureTestingModule({
      providers: [CookingReminderJobService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingReminderJobService);
  });

  it('laedt die Kochdienst-Jobs', () => {
    const job = { id: '1', name: 'Erinnerung', senderAccountId: 'a', subject: 's',
                  sendTime: '07:00', active: true, templateId: 't',
                  templateName: 'V', templateBodyHtml: '<p>x</p>' };
    api.get.and.returnValue(of([job]));

    service.list().subscribe((jobs) => expect(jobs.length).toBe(1));

    expect(api.get).toHaveBeenCalledWith('/cooking-reminder-jobs');
  });

  it('legt einen Job samt Vorlage an', () => {
    api.post.and.returnValue(of({}));

    service.create({
      name: 'Erinnerung', senderAccountId: 'a', subject: 's', sendTime: '07:00',
      active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    }).subscribe();

    expect(api.post).toHaveBeenCalledWith('/cooking-reminder-jobs', jasmine.objectContaining({
      templateName: 'V'
    }));
  });
});
