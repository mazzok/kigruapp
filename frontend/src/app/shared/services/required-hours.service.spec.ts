import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { RequiredHoursService } from './required-hours.service';

describe('RequiredHoursService', () => {
  let service: RequiredHoursService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HttpClientTestingModule], providers: [RequiredHoursService] });
    service = TestBed.inject(RequiredHoursService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('GETs config for a semester', () => {
    service.get('sem1').subscribe();
    const req = http.expectOne('/api/v1/required-hours?semesterId=sem1');
    expect(req.request.method).toBe('GET');
    req.flush({ semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [] });
  });

  it('PUTs config for a semester', () => {
    service.save('sem1', { semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }] }).subscribe();
    const req = http.expectOne('/api/v1/required-hours?semesterId=sem1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.tiers[0].fromChild).toBe(2);
    req.flush({ semesterId: 'sem1', defaultMinutesPerMonth: 480, tiers: [{ fromChild: 2, minutesPerMonth: 360 }] });
  });
});
