import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { ClosureDefinitionService } from './closure-definition.service';
import { ClosurePeriodService } from './closure-period.service';
import { HolidayService } from './holiday.service';

describe('Closure services', () => {
  let http: HttpTestingController;
  let definitions: ClosureDefinitionService;
  let periods: ClosurePeriodService;
  let holidays: HolidayService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    definitions = TestBed.inject(ClosureDefinitionService);
    periods = TestBed.inject(ClosurePeriodService);
    holidays = TestBed.inject(HolidayService);
  });

  afterEach(() => http.verify());

  it('loads only active definitions by default', () => {
    definitions.getAll().subscribe();
    const req = http.expectOne('/api/v1/closure-definitions');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('can request inactive definitions as well', () => {
    definitions.getAll(true).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions?includeInactive=true');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('creates a definition', () => {
    definitions.create({ label: 'Ferien', color: '#d94f4f' }).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ label: 'Ferien', color: '#d94f4f' });
    req.flush({});
  });

  it('revises a definition through its own endpoint', () => {
    definitions.revise('abc', { label: 'Neu', color: '#4f86d9' }).subscribe();
    const req = http.expectOne('/api/v1/closure-definitions/abc/revise');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('deactivates through DELETE', () => {
    definitions.deactivate('abc').subscribe();
    const req = http.expectOne('/api/v1/closure-definitions/abc');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('loads periods for a window', () => {
    periods.getRange('2026-09-01', '2027-02-28').subscribe();
    const req = http.expectOne('/api/v1/closure-periods?from=2026-09-01&to=2027-02-28');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('applies a day selection', () => {
    periods.apply({ days: ['2026-09-07'], definitionId: 'abc', mode: 'assign' }).subscribe();
    const req = http.expectOne('/api/v1/closure-periods/apply');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.mode).toBe('assign');
    req.flush([]);
  });

  it('loads holidays for a window', () => {
    holidays.getRange('2026-09-01', '2027-02-28').subscribe();
    const req = http.expectOne('/api/v1/holidays?from=2026-09-01&to=2027-02-28');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });
});
