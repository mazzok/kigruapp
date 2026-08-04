import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { SchliesstageViewComponent } from './schliesstage-view.component';
import { ClosureDefinitionService } from '../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../shared/services/closure-period.service';
import { HolidayService } from '../shared/services/holiday.service';
import { SemesterService } from '../shared/services/semester.service';
import { ClosureDefinition } from '../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const umbau: ClosureDefinition = {
  id: 'def-umbau', label: 'Umbau', color: '#888888', active: false, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageViewComponent', () => {
  let periodService: jasmine.SpyObj<ClosurePeriodService>;
  let semesterService: jasmine.SpyObj<SemesterService>;

  function build(): ComponentFixture<SchliesstageViewComponent> {
    const fixture = TestBed.createComponent(SchliesstageViewComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(async () => {
    const definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.returnValue(of([ferien, umbau]));

    periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.returnValue(of([
      { id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def-ferien' },
    ]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([]));

    semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);

    await TestBed.configureTestingModule({
      imports: [SchliesstageViewComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
      ],
    }).compileComponents();

    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2026, 8, 15)); // 15.09.2026, lokale Zeit
  });

  afterEach(() => jasmine.clock().uninstall());

  it('waehlt das Semester, in das das heutige Datum faellt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-neu', start: '2027-03-01T00:00:00Z', end: '2027-08-31T00:00:00Z', createdAt: '2026-08-01T00:00:00Z' },
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ]));

    const fixture = build();

    // Nicht semesters[0] — das waere das noch nicht begonnene Semester.
    expect(fixture.componentInstance.from).toBe('2026-09-01');
    expect(fixture.componentInstance.to).toBe('2027-02-28');
    expect(periodService.getRange).toHaveBeenCalledWith('2026-09-01', '2027-02-28');
  });

  it('zeigt einen Hinweis, wenn heute in keinem Semester liegt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-alt', start: '2026-02-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ]));

    const fixture = build();

    expect(fixture.componentInstance.from).toBe('');
    expect(periodService.getRange).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.no-semester')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-closure-calendar')).toBeNull();
  });

  it('zeigt einen Hinweis, wenn gar kein Semester existiert', () => {
    semesterService.getAll.and.returnValue(of([]));

    const fixture = build();

    expect(fixture.nativeElement.querySelector('.no-semester')).not.toBeNull();
  });

  it('rendert den Kalender schreibgeschuetzt', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ]));

    const fixture = build();

    expect(fixture.componentInstance.readonly).toBe(true);
  });

  it('zeigt in der Legende nur vorkommende Definitionen', () => {
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-laufend', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
    ]));

    const fixture = build();

    expect(fixture.componentInstance.legend.map(d => d.id)).toEqual(['def-ferien']);
  });
});
