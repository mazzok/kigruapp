import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { HoursSummaryService } from './hours-summary.service';
import { HourEntryService } from './hour-entry.service';
import { OurHours } from '../models/hour-entry.model';

const ourHours: OurHours = {
  familyId: 'fam-1',
  familyMonthlyMinutes: 300,
  monthsInSemester: 6,
  sollMinutes: 1800,
  istMinutes: 750,
  months: [{ month: '2026-09', sollMinutes: 300, istMinutes: 750 }],
  entries: [],
};

describe('HoursSummaryService', () => {
  let hourService: jasmine.SpyObj<HourEntryService>;
  let service: HoursSummaryService;

  beforeEach(() => {
    hourService = jasmine.createSpyObj<HourEntryService>('HourEntryService', ['our']);
    hourService.our.and.returnValue(of(ourHours));

    TestBed.configureTestingModule({
      providers: [
        HoursSummaryService,
        { provide: HourEntryService, useValue: hourService },
      ],
    });
    service = TestBed.inject(HoursSummaryService);
  });

  it('starts empty and does not call the API on its own', () => {
    let seen: OurHours | null | undefined;
    service.summary$.subscribe((s: OurHours | null) => (seen = s));

    expect(seen).toBeNull();
    expect(service.current).toBeNull();
    expect(hourService.our).not.toHaveBeenCalled();
  });

  it('loads the newest semester on reload and pushes it to subscribers', () => {
    let seen: OurHours | null | undefined;
    service.summary$.subscribe((s: OurHours | null) => (seen = s));

    service.reload();

    expect(hourService.our).toHaveBeenCalledWith('');
    expect(seen).toEqual(ourHours);
    expect(service.current).toEqual(ourHours);
  });

  it('refetches on every reload', () => {
    service.reload();
    service.reload();

    expect(hourService.our).toHaveBeenCalledTimes(2);
  });

  it('falls back to empty on error instead of throwing', () => {
    let seen: OurHours | null = null;
    service.summary$.subscribe((s: OurHours | null) => (seen = s));

    service.reload(); // subject now holds ourHours
    hourService.our.and.returnValue(throwError(() => new Error('boom')));

    expect(() => service.reload()).not.toThrow();
    expect(seen).toBeNull();
  });

  it('clears the held summary', () => {
    service.reload();
    service.clear();

    expect(service.current).toBeNull();
  });
});
