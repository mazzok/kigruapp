import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CookingOverviewJobService } from './cooking-overview-job.service';
import { ApiService } from '../../core/services/api.service';
import { CookingOverviewJob } from '../models/cooking-overview-job.model';

describe('CookingOverviewJobService', () => {
  let service: CookingOverviewJobService;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj('ApiService', ['get', 'post', 'put', 'delete']);
    TestBed.configureTestingModule({
      providers: [CookingOverviewJobService, { provide: ApiService, useValue: api }],
    });
    service = TestBed.inject(CookingOverviewJobService);
  });

  it('laedt die Uebersichtsjobs', () => {
    const jobs: CookingOverviewJob[] = [{ id: '1', name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's',
                    cron: '0 0 7 ? * MON', allParents: true, recipientSelections: [], active: true,
                    templateId: 't', templateName: 'V', templateBodyHtml: '<p>x</p>' }];
    api.get.and.returnValue(of(jobs));

    service.list().subscribe((result: typeof jobs) => expect(result.length).toBe(1));

    expect(api.get).toHaveBeenCalledWith('/cooking-overview-jobs');
  });

  it('legt einen Job samt Vorlage an', () => {
    const request = {
      name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's', cron: '0 0 7 ? * MON',
      allParents: true, recipientSelections: [], active: false, templateName: 'V', templateBodyHtml: '<p>x</p>',
    };
    api.post.and.returnValue(of({}));

    service.create(request).subscribe();

    expect(api.post).toHaveBeenCalledWith('/cooking-overview-jobs', request);
  });

  it('aktualisiert einen Job', () => {
    const request = {
      name: 'Wochenuebersicht', senderAccountId: 'a', subject: 's', cron: '0 0 8 ? * MON',
      allParents: false, recipientSelections: [{ kind: 'TEAM' as const, fieldInstanceId: 't1' }],
      active: true, templateName: 'V', templateBodyHtml: '<p>x</p>',
    };
    api.put.and.returnValue(of({}));

    service.update('1', request).subscribe();

    expect(api.put).toHaveBeenCalledWith('/cooking-overview-jobs/1', request);
  });

  it('loescht einen Job', () => {
    api.delete.and.returnValue(of(undefined));

    service.delete('1').subscribe();

    expect(api.delete).toHaveBeenCalledWith('/cooking-overview-jobs/1');
  });
});
