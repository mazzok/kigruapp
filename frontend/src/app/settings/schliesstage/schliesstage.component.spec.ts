import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { SchliesstageComponent } from './schliesstage.component';
import { ClosureDefinitionService } from '../../shared/services/closure-definition.service';
import { ClosurePeriodService } from '../../shared/services/closure-period.service';
import { HolidayService } from '../../shared/services/holiday.service';
import { SemesterService } from '../../shared/services/semester.service';
import { ApplyPeriodsRequest, ClosureDefinition, ClosurePeriod } from '../../shared/models/closure.model';

const ferien: ClosureDefinition = {
  id: 'def-ferien', label: 'Ferien', color: '#d94f4f', active: true, createdAt: '2026-07-02T00:00:00Z',
};
const fortbildung: ClosureDefinition = {
  id: 'def-fortbildung', label: 'Fortbildung', color: '#e0a020', active: true, createdAt: '2026-07-01T00:00:00Z',
};

describe('SchliesstageComponent', () => {
  let fixture: ComponentFixture<SchliesstageComponent>;
  let component: SchliesstageComponent;
  let periodService: jasmine.SpyObj<ClosurePeriodService>;
  let periods: ClosurePeriod[];

  beforeEach(async () => {
    periods = [{ id: 'p1', from: '2026-09-07', to: '2026-09-09', definitionId: 'def-ferien' }];

    const definitionService = jasmine.createSpyObj<ClosureDefinitionService>(
      'ClosureDefinitionService', ['getAll', 'create', 'update', 'revise', 'deactivate']);
    definitionService.getAll.and.callFake(() => of([ferien, fortbildung]));

    periodService = jasmine.createSpyObj<ClosurePeriodService>(
      'ClosurePeriodService', ['getRange', 'apply']);
    periodService.getRange.and.callFake(() => of(periods));
    periodService.apply.and.callFake(() => of([]));

    const holidayService = jasmine.createSpyObj<HolidayService>('HolidayService', ['getRange']);
    holidayService.getRange.and.returnValue(of([{ date: '2026-10-26', name: 'Nationalfeiertag' }]));

    const semesterService = jasmine.createSpyObj<SemesterService>('SemesterService', ['getAll', 'create']);
    semesterService.getAll.and.returnValue(of([
      { id: 'sem-2', start: '2026-09-01T00:00:00Z', end: '2027-02-28T00:00:00Z', createdAt: '2026-07-01T00:00:00Z' },
      { id: 'sem-1', start: '2026-02-01T00:00:00Z', end: '2026-08-31T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    ]));

    await TestBed.configureTestingModule({
      imports: [SchliesstageComponent, NoopAnimationsModule],
      providers: [
        { provide: ClosureDefinitionService, useValue: definitionService },
        { provide: ClosurePeriodService, useValue: periodService },
        { provide: HolidayService, useValue: holidayService },
        { provide: SemesterService, useValue: semesterService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SchliesstageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('waehlt das zuletzt angelegte Semester vor', () => {
    expect(component.selectedSemesterId).toBe('sem-2');
  });

  it('leitet den Semesterzeitraum an den Kalender weiter', () => {
    expect(component.from).toBe('2026-09-01');
    expect(component.to).toBe('2027-02-28');
  });

  it('laedt den Kalender nur beim Semesterwechsel neu', () => {
    expect(periodService.getRange).toHaveBeenCalledTimes(1);

    component.onSelectionChange(['2026-09-07']);
    fixture.detectChanges();
    expect(periodService.getRange).toHaveBeenCalledTimes(1);

    component.onSemesterChange('sem-1');
    fixture.detectChanges();
    expect(periodService.getRange).toHaveBeenCalledTimes(2);
  });

  it('meldet none, solange nichts markiert ist', () => {
    expect(component.tristate('def-ferien')).toBe('none');
  });

  it('meldet all, wenn die Definition auf allen gewaehlten Tagen liegt', () => {
    component.onSelectionChange(['2026-09-07', '2026-09-08']);
    expect(component.tristate('def-ferien')).toBe('all');
  });

  it('meldet some bei teilweiser Belegung', () => {
    component.onSelectionChange(['2026-09-08', '2026-09-10']);
    expect(component.tristate('def-ferien')).toBe('some');
  });

  it('meldet none, wenn die Definition auf keinem gewaehlten Tag liegt', () => {
    component.onSelectionChange(['2026-09-10', '2026-09-11']);
    expect(component.tristate('def-fortbildung')).toBe('none');
  });

  it('weist zu, wenn die Definition noch nicht ueberall gilt', () => {
    component.onSelectionChange(['2026-09-10']);
    component.toggleDefinition(ferien);

    const request = periodService.apply.calls.mostRecent().args[0] as ApplyPeriodsRequest;
    expect(request.mode).toBe('assign');
    expect(request.definitionId).toBe('def-ferien');
    expect(request.days).toEqual(['2026-09-10']);
  });

  it('entfernt, wenn die Definition bereits ueberall gilt', () => {
    component.onSelectionChange(['2026-09-07', '2026-09-08']);
    component.toggleDefinition(ferien);

    const request = periodService.apply.calls.mostRecent().args[0] as ApplyPeriodsRequest;
    expect(request.mode).toBe('remove');
  });

  it('laedt die Zeitraeume nach dem Zuweisen neu und leert die Auswahl', () => {
    component.onSelectionChange(['2026-09-10']);
    component.toggleDefinition(ferien);
    fixture.detectChanges();

    expect(periodService.getRange).toHaveBeenCalledTimes(2);
    expect(component.selectedDays).toEqual([]);
  });

  it('blendet die Zuweisungsleiste nur bei bestehender Auswahl ein', () => {
    expect(fixture.nativeElement.querySelector('.assign-bar')).toBeNull();

    component.onSelectionChange(['2026-09-10']);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.assign-bar')).not.toBeNull();
  });
});
