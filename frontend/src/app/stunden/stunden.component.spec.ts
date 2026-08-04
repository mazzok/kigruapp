import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of, BehaviorSubject } from 'rxjs';
import { StundenComponent } from './stunden.component';
import { HourEntryService } from '../shared/services/hour-entry.service';
import { HoursSummaryService } from '../shared/services/hours-summary.service';
import { NotificationService } from '../shared/services/notification.service';
import { CurrentUserService } from '../core/services/current-user.service';
import { OurHours } from '../shared/models/hour-entry.model';

describe('StundenComponent', () => {
  let fixture: ComponentFixture<StundenComponent>;
  const summary = new BehaviorSubject<OurHours | null>(null);

  const our: OurHours = {
    familyId: 'f1', familyMonthlyMinutes: 480, monthsInSemester: 1,
    sollMinutes: 480, istMinutes: 180, allGroups: true,
    children: [{
      childId: 'c1', name: 'Lena', groupLabel: 'Käfergruppe', groupColor: '#43a047',
      baseMinutesPerMonth: 480, entryDate: null, exitDate: null, sollMinutes: 480,
    }],
    months: [{ month: '2026-10', sollMinutes: 480, istMinutes: 180, children: [
      { childId: 'c1', minutes: 480, fractionPercent: 100, discountPercent: 0 },
    ] }],
    entries: [{
      id: '1', personId: 'p1', personName: 'Martin', roleLabel: 'Garten',
      date: '2026-10-12', minutes: 180, comment: '',
    }],
  };

  const hourService = jasmine.createSpyObj('HourEntryService',
    ['listMine', 'roleOptions', 'create', 'update', 'delete']);
  const hoursSummary = {
    summary$: summary.asObservable(),
    current: null as OurHours | null,
    reload: jasmine.createSpy('reload'),
  };
  const dialog = jasmine.createSpyObj('MatDialog', ['open']);

  beforeEach(async () => {
    hourService.listMine.and.returnValue(of([]));
    hourService.roleOptions.and.returnValue(of([]));
    hourService.create.and.returnValue(of({}));
    hourService.delete.and.returnValue(of({}));
    hoursSummary.reload.calls.reset();

    await TestBed.configureTestingModule({
      imports: [StundenComponent, NoopAnimationsModule],
      providers: [
        { provide: HourEntryService, useValue: hourService },
        { provide: HoursSummaryService, useValue: hoursSummary },
        { provide: MatDialog, useValue: dialog },
        { provide: NotificationService, useValue: jasmine.createSpyObj('NotificationService', ['success', 'error', 'extractError']) },
        { provide: CurrentUserService, useValue: { currentPerson: { id: 'p1' } } },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(StundenComponent);
    fixture.detectChanges();
    summary.next(our);
    fixture.detectChanges();
  });

  it('zeigt Zusammensetzung und Eintragstabelle', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-hours-breakdown')).not.toBeNull();
    expect(el.querySelector('app-hours-entries')).not.toBeNull();
    expect(el.textContent).toContain('Unsere Stunden');
  });

  it('öffnet den Dialog beim Anlegen und lädt nach dem Speichern neu', () => {
    dialog.open.and.returnValue({ afterClosed: () => of({
      roleFieldInstanceId: null, date: '2026-10-12', minutes: 90, comment: '',
    }) });

    fixture.componentInstance.newEntry();

    expect(dialog.open).toHaveBeenCalled();
    expect(hourService.create).toHaveBeenCalled();
    expect(hoursSummary.reload).toHaveBeenCalled();
  });

  it('speichert nicht, wenn der Dialog abgebrochen wird', () => {
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) });
    hourService.create.calls.reset();

    fixture.componentInstance.newEntry();

    expect(hourService.create).not.toHaveBeenCalled();
  });

  it('löscht einen eigenen Eintrag und lädt neu', () => {
    fixture.componentInstance.deleteEntry(our.entries[0]);

    expect(hourService.delete).toHaveBeenCalledWith('1');
    expect(hoursSummary.reload).toHaveBeenCalled();
  });
});
