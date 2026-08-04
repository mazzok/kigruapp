import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatTooltip } from '@angular/material/tooltip';
import { BehaviorSubject } from 'rxjs';
import { HoursRingComponent } from './hours-ring.component';
import { HoursSummaryService } from '../../services/hours-summary.service';
import { OurHours } from '../../models/hour-entry.model';

function ourHours(overrides: Partial<OurHours> = {}): OurHours {
  const months = ['2026-09', '2026-10', '2026-11', '2026-12', '2027-01', '2027-02']
    .map((month) => ({ month, sollMinutes: 300, istMinutes: 0, children: [] }));
  return {
    familyId: 'fam-1',
    familyMonthlyMinutes: 300,
    monthsInSemester: 6,
    sollMinutes: 1800,
    istMinutes: 750,
    allGroups: true,
    children: [],
    months,
    entries: [],
    ...overrides,
  };
}

describe('HoursRingComponent', () => {
  let subject: BehaviorSubject<OurHours | null>;
  let fixture: ComponentFixture<HoursRingComponent>;

  beforeEach(async () => {
    subject = new BehaviorSubject<OurHours | null>(null);

    await TestBed.configureTestingModule({
      imports: [HoursRingComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        {
          provide: HoursSummaryService,
          useValue: { summary$: subject.asObservable(), reload: () => {}, clear: () => {} },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(HoursRingComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    jasmine.clock().uninstall();
  });

  it('renders nothing without a summary', () => {
    expect(fixture.componentInstance.state).toBeNull();
    expect(fixture.nativeElement.querySelector('.hours-ring')).toBeNull();
  });

  it('renders nothing when the summary has no Soll', () => {
    subject.next(ourHours({ sollMinutes: 0 }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.hours-ring')).toBeNull();
  });

  it('renders the ring, the percentage and the hours once data arrives', () => {
    subject.next(ourHours({ istMinutes: 900 }));
    fixture.detectChanges();

    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('.hours-ring')).not.toBeNull();
    expect(host.querySelector('.ring-label')!.textContent).toContain('50%');
    expect(host.querySelector('.hours-ring-done')!.textContent).toContain('15:00 h');
    expect(host.querySelector('.hours-ring-goal')!.textContent).toContain('von 30:00 h');
  });

  it('marks the arc with the calculated level', () => {
    subject.next(ourHours({ istMinutes: 1800 }));
    fixture.detectChanges();

    const arc = fixture.nativeElement.querySelector('.ring-arc') as SVGElement;
    expect(arc.classList).toContain('level-level5');
  });

  it('marks the arc with level3 when fulfillment is 50%', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(2027, 1, 15)); // Feb 2027, within fixture months; 50% = istMinutes 900 due sollToDateMinutes 1800
    fixture = TestBed.createComponent(HoursRingComponent);
    fixture.detectChanges();

    subject.next(ourHours({ istMinutes: 900 }));
    fixture.detectChanges();

    const arc = fixture.nativeElement.querySelector('.ring-arc') as SVGElement;
    expect(arc.classList).toContain('level-level3');
  });

  it('links to the hours page and exposes an aria label', () => {
    subject.next(ourHours());
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('.hours-ring') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/stunden');
    expect(link.getAttribute('aria-label')).toContain('von 30:00 h geleistet');
  });

  it('passes the explaining text to the tooltip directive', () => {
    subject.next(ourHours());
    fixture.detectChanges();

    const tooltip = fixture.debugElement
      .query(By.directive(MatTooltip))
      .injector.get(MatTooltip);
    expect(tooltip.message).toContain('Fällig bis heute');
    expect(tooltip.message).toContain('Ø geleistet');
  });

  it('turns the progress into a dash offset of the full circumference', () => {
    subject.next(ourHours({ istMinutes: 900 }));
    fixture.detectChanges();

    const component = fixture.componentInstance;
    expect(component.dashOffset).toBeCloseTo(component.circumference * 0.5, 5);
  });

  it('leaves no gap in the arc when the ring is full', () => {
    subject.next(ourHours({ istMinutes: 2016 }));
    fixture.detectChanges();

    expect(fixture.componentInstance.dashOffset).toBe(0);
    expect(fixture.nativeElement.querySelector('.ring-label')!.textContent).toContain('112%');
  });
});
