# Stunden-Ring im Header — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein kreisförmiger Fortschrittsring im App-Header zeigt die geleisteten und die zu leistenden Stunden der Familie im aktuellen Semester, farbcodiert nach dem Rückstand gegenüber dem bis heute fälligen Soll, mit erklärendem Tooltip.

**Architecture:** Reine Rechenlogik in einer Utility-Datei, ein `HoursSummaryService` als gemeinsame Datenquelle (`BehaviorSubject` + `reload()`), eine Anzeige-Komponente mit Inline-SVG-Ring im Toolbar, und die bestehende Seite „Unsere Stunden“ liest denselben Zustand statt selbst zu laden. Kein Backend-Änderungsbedarf — `GET /api/v1/hour-entries/our?semesterId=` liefert alle Werte.

**Tech Stack:** Angular 18+ standalone components, RxJS `BehaviorSubject`, Angular Material (`MatTooltipModule`), Jasmine/Karma, SCSS, Inline-SVG.

Spec: `docs/superpowers/specs/2026-07-31-stunden-ring-header-design.md`

## Global Constraints

- Frontend-Verzeichnis: `frontend/`. Testbefehl: `npm test -- --watch=false --browsers=ChromeHeadless` (aus `frontend/`).
- Alle Benutzertexte auf Deutsch. Zeitangaben im Format `HH:MM h` über `formatMinutes()` aus `src/app/shared/util/time-format.util.ts`.
- Kein Angular-Signal: Zustand über `BehaviorSubject`, wie in `CurrentUserService`.
- Keine neuen npm-Abhängigkeiten.
- Standalone-Komponenten mit explizitem `imports`-Array, wie im bestehenden Code.
- Kommentare im Code auf Deutsch, sparsam, nur wo die Absicht nicht aus dem Code folgt (Hausstil).
- Ring-Statuswerte heißen genau `done`, `onTrack`, `slightlyBehind`, `behind`.
- Semester: immer das jüngste; die API wird mit leerem `semesterId` aufgerufen (`our('')`).

---

### Task 1: Rechenlogik `hours-ring.util.ts`

Reine Funktionen, kein Angular. Berechnet aus `OurHours` den Anzeigezustand samt Tooltip-Text.

**Files:**
- Create: `frontend/src/app/shared/components/hours-ring/hours-ring.util.ts`
- Test: `frontend/src/app/shared/components/hours-ring/hours-ring.util.spec.ts`

**Interfaces:**
- Consumes: `OurHours` aus `src/app/shared/models/hour-entry.model.ts` (Felder `familyId`, `familyMonthlyMinutes`, `monthsInSemester`, `sollMinutes`, `istMinutes`, `months[{month, sollMinutes, istMinutes}]`, `entries`), `formatMinutes(total: number): string` aus `src/app/shared/util/time-format.util.ts`.
- Produces:
  - `type RingStatus = 'done' | 'onTrack' | 'slightlyBehind' | 'behind'`
  - `interface RingState { ringPercent: number; realPercent: number; status: RingStatus; istMinutes: number; sollMinutes: number; sollToDateMinutes: number; deltaMinutes: number; elapsedMonths: number; totalMonths: number; monthlySollMinutes: number; avgDoneMinutes: number; tooltip: string; ariaLabel: string; }`
  - `function currentYearMonth(now: Date): string` — liefert `"YYYY-MM"`
  - `function buildRingState(our: OurHours | null, today: string): RingState | null`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.util.spec.ts`:

```ts
import { OurHours } from '../../models/hour-entry.model';
import { buildRingState, currentYearMonth } from './hours-ring.util';

/** 6-Monats-Semester 09/2026–02/2027, Soll 5:00 h je Monat = 30:00 h. */
function ourHours(overrides: Partial<OurHours> = {}): OurHours {
  const months = ['2026-09', '2026-10', '2026-11', '2026-12', '2027-01', '2027-02']
    .map((month) => ({ month, sollMinutes: 300, istMinutes: 0 }));
  return {
    familyId: 'fam-1',
    familyMonthlyMinutes: 300,
    monthsInSemester: 6,
    sollMinutes: 1800,
    istMinutes: 0,
    months,
    entries: [],
    ...overrides,
  };
}

describe('currentYearMonth', () => {
  it('formats a date as YYYY-MM', () => {
    expect(currentYearMonth(new Date(2026, 10, 5))).toBe('2026-11');
  });

  it('pads single digit months', () => {
    expect(currentYearMonth(new Date(2027, 0, 31))).toBe('2027-01');
  });
});

describe('buildRingState', () => {
  it('sums the Soll of all months up to and including today', () => {
    const state = buildRingState(ourHours({ istMinutes: 750 }), '2026-11')!;

    expect(state.sollToDateMinutes).toBe(900); // 09 + 10 + 11
    expect(state.elapsedMonths).toBe(3);
    expect(state.totalMonths).toBe(6);
    expect(state.deltaMinutes).toBe(-150);
  });

  it('counts the whole semester in the last semester month', () => {
    const state = buildRingState(ourHours({ istMinutes: 1800 }), '2027-02')!;

    expect(state.sollToDateMinutes).toBe(1800);
    expect(state.elapsedMonths).toBe(6);
  });

  it('reports done when the semester Soll is reached', () => {
    const state = buildRingState(ourHours({ istMinutes: 1800 }), '2026-09')!;

    expect(state.status).toBe('done');
  });

  it('reports onTrack when Ist covers the Soll due so far', () => {
    const state = buildRingState(ourHours({ istMinutes: 900 }), '2026-11')!;

    expect(state.status).toBe('onTrack');
    expect(state.deltaMinutes).toBe(0);
  });

  it('reports slightlyBehind when less than one month of Soll is missing', () => {
    const state = buildRingState(ourHours({ istMinutes: 601 }), '2026-11')!;

    expect(state.deltaMinutes).toBe(-299);
    expect(state.status).toBe('slightlyBehind');
  });

  it('reports behind at exactly one month of Soll missing', () => {
    const state = buildRingState(ourHours({ istMinutes: 600 }), '2026-11')!;

    expect(state.deltaMinutes).toBe(-300);
    expect(state.status).toBe('behind');
  });

  it('caps the ring at 100 percent but keeps the real percentage', () => {
    const state = buildRingState(ourHours({ istMinutes: 2016 }), '2027-02')!;

    expect(state.ringPercent).toBe(100);
    expect(state.realPercent).toBe(112);
  });

  it('rounds the ring percentage', () => {
    const state = buildRingState(ourHours({ istMinutes: 900 }), '2026-11')!;

    expect(state.ringPercent).toBe(50);
    expect(state.realPercent).toBe(50);
  });

  it('ignores month rows after the end of the semester for the Soll due so far', () => {
    const our = ourHours({ istMinutes: 900 });
    our.months.push({ month: '2027-03', sollMinutes: 0, istMinutes: 120 });

    const state = buildRingState(our, '2027-03')!;

    expect(state.sollToDateMinutes).toBe(1800);
    expect(state.elapsedMonths).toBe(6);
  });

  it('returns null when there is no family', () => {
    expect(buildRingState(ourHours({ familyId: null }), '2026-11')).toBeNull();
  });

  it('returns null when no Soll is configured', () => {
    expect(buildRingState(ourHours({ sollMinutes: 0 }), '2026-11')).toBeNull();
  });

  it('returns null without data', () => {
    expect(buildRingState(null, '2026-11')).toBeNull();
  });

  it('computes the average hours done per elapsed month', () => {
    const state = buildRingState(ourHours({ istMinutes: 750 }), '2026-11')!;

    expect(state.avgDoneMinutes).toBe(250);
    expect(state.monthlySollMinutes).toBe(300);
  });

  it('reports no average before the first month with Soll', () => {
    const state = buildRingState(ourHours({ istMinutes: 0 }), '2026-08')!;

    expect(state.elapsedMonths).toBe(0);
    expect(state.avgDoneMinutes).toBe(0);
    expect(state.status).toBe('onTrack');
  });

  it('builds a tooltip that explains the calculation', () => {
    const state = buildRingState(ourHours({ istMinutes: 750 }), '2026-11')!;

    expect(state.tooltip).toBe(
      'Soll gesamt: 30:00 h (6 Monate × 05:00 h)\n' +
      'Geleistet: 12:30 h\n' +
      'Fällig bis heute: 15:00 h (3 von 6 Monaten)\n' +
      'Rückstand: 02:30 h\n' +
      'Ø geleistet: 04:10 h/Monat · benötigt 05:00 h/Monat',
    );
  });

  it('shows a lead instead of a deficit when ahead', () => {
    const state = buildRingState(ourHours({ istMinutes: 1200 }), '2026-11')!;

    expect(state.tooltip).toContain('Vorsprung: 05:00 h');
    expect(state.tooltip).not.toContain('Rückstand');
  });

  it('states that the Soll is met when the semester total is reached', () => {
    const state = buildRingState(ourHours({ istMinutes: 1800 }), '2026-11')!;

    expect(state.tooltip).toContain('Soll erfüllt');
    expect(state.tooltip).not.toContain('Vorsprung');
  });

  it('builds an aria label naming Ist, Soll and status', () => {
    const state = buildRingState(ourHours({ istMinutes: 750 }), '2026-11')!;

    expect(state.ariaLabel)
      .toBe('Stunden der Familie: 12:30 h von 30:00 h geleistet, leicht im Rückstand');
  });

  it('names the plain status in the aria label when clearly behind', () => {
    const state = buildRingState(ourHours({ istMinutes: 600 }), '2026-11')!;

    expect(state.ariaLabel).toContain('geleistet, im Rückstand');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.util.spec.ts` (aus `frontend/`)
Expected: FAIL — Modul `./hours-ring.util` bzw. die Exporte existieren nicht.

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.util.ts`:

```ts
import { OurHours } from '../../models/hour-entry.model';
import { formatMinutes } from '../../util/time-format.util';

export type RingStatus = 'done' | 'onTrack' | 'slightlyBehind' | 'behind';

export interface RingState {
  /** Ringfüllung in Prozent, bei 100 gekappt. */
  ringPercent: number;
  /** Tatsächlicher Prozentwert für die Beschriftung, ungekappt. */
  realPercent: number;
  status: RingStatus;
  istMinutes: number;
  sollMinutes: number;
  sollToDateMinutes: number;
  /** Ist minus Soll bis heute: negativ = Rückstand. */
  deltaMinutes: number;
  elapsedMonths: number;
  totalMonths: number;
  monthlySollMinutes: number;
  avgDoneMinutes: number;
  tooltip: string;
  ariaLabel: string;
}

const STATUS_TEXT: Record<RingStatus, string> = {
  done: 'Soll erfüllt',
  onTrack: 'im Plan',
  slightlyBehind: 'leicht im Rückstand',
  behind: 'im Rückstand',
};

/** Aktueller Monat als "YYYY-MM" (lokale Zeit). */
export function currentYearMonth(now: Date): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Anzeigezustand des Rings. Gibt null zurück, wenn nichts anzuzeigen ist:
 * keine Familie, kein konfiguriertes Soll oder keine Daten.
 */
export function buildRingState(our: OurHours | null, today: string): RingState | null {
  if (!our || !our.familyId || our.sollMinutes <= 0) {
    return null;
  }

  const monthsWithSoll = our.months.filter((m) => m.sollMinutes > 0);
  const dueMonths = monthsWithSoll.filter((m) => m.month <= today);
  const sollToDateMinutes = dueMonths.reduce((sum, m) => sum + m.sollMinutes, 0);
  const elapsedMonths = dueMonths.length;
  const totalMonths = our.monthsInSemester > 0 ? our.monthsInSemester : monthsWithSoll.length;

  const istMinutes = our.istMinutes;
  const sollMinutes = our.sollMinutes;
  const deltaMinutes = istMinutes - sollToDateMinutes;
  // Abgeleitet statt familyMonthlyMinutes: bleibt bei aliquotierten Semestern
  // mit dem angezeigten Soll konsistent.
  const monthlySollMinutes = totalMonths > 0 ? Math.round(sollMinutes / totalMonths) : sollMinutes;
  const avgDoneMinutes = elapsedMonths > 0 ? Math.round(istMinutes / elapsedMonths) : 0;

  const realPercent = Math.round((istMinutes / sollMinutes) * 100);
  const ringPercent = Math.min(100, realPercent);

  let status: RingStatus;
  if (istMinutes >= sollMinutes) {
    status = 'done';
  } else if (deltaMinutes >= 0) {
    status = 'onTrack';
  } else if (-deltaMinutes < monthlySollMinutes) {
    status = 'slightlyBehind';
  } else {
    status = 'behind';
  }

  const balanceLine = status === 'done'
    ? 'Soll erfüllt'
    : deltaMinutes >= 0
      ? `Vorsprung: ${formatMinutes(deltaMinutes)} h`
      : `Rückstand: ${formatMinutes(-deltaMinutes)} h`;

  const tooltip = [
    `Soll gesamt: ${formatMinutes(sollMinutes)} h (${totalMonths} Monate × ${formatMinutes(monthlySollMinutes)} h)`,
    `Geleistet: ${formatMinutes(istMinutes)} h`,
    `Fällig bis heute: ${formatMinutes(sollToDateMinutes)} h (${elapsedMonths} von ${totalMonths} Monaten)`,
    balanceLine,
    `Ø geleistet: ${formatMinutes(avgDoneMinutes)} h/Monat · benötigt ${formatMinutes(monthlySollMinutes)} h/Monat`,
  ].join('\n');

  const ariaLabel = `Stunden der Familie: ${formatMinutes(istMinutes)} h von `
    + `${formatMinutes(sollMinutes)} h geleistet, ${STATUS_TEXT[status]}`;

  return {
    ringPercent, realPercent, status,
    istMinutes, sollMinutes, sollToDateMinutes, deltaMinutes,
    elapsedMonths, totalMonths, monthlySollMinutes, avgDoneMinutes,
    tooltip, ariaLabel,
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.util.spec.ts`
Expected: PASS, alle Specs grün.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/components/hours-ring/hours-ring.util.ts frontend/src/app/shared/components/hours-ring/hours-ring.util.spec.ts
git commit -m "feat(fe): Rechenlogik fuer Stunden-Fortschrittsring"
```

---

### Task 2: `HoursSummaryService` als gemeinsame Datenquelle

Ein Dienst, der `GET /hour-entries/our` einmal lädt, das Ergebnis hält und auf Anfrage neu lädt.

**Files:**
- Create: `frontend/src/app/shared/services/hours-summary.service.ts`
- Test: `frontend/src/app/shared/services/hours-summary.service.spec.ts`

**Interfaces:**
- Consumes: `HourEntryService.our(semesterId: string): Observable<OurHours>` aus `src/app/shared/services/hour-entry.service.ts`.
- Produces: `class HoursSummaryService` mit
  - `summary$: Observable<OurHours | null>` (startet mit `null`)
  - `get current(): OurHours | null`
  - `reload(): void`
  - `clear(): void`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/services/hours-summary.service.spec.ts`:

```ts
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
    service.summary$.subscribe((s) => (seen = s));

    expect(seen).toBeNull();
    expect(service.current).toBeNull();
    expect(hourService.our).not.toHaveBeenCalled();
  });

  it('loads the newest semester on reload and pushes it to subscribers', () => {
    let seen: OurHours | null = null;
    service.summary$.subscribe((s) => (seen = s));

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
    hourService.our.and.returnValue(throwError(() => new Error('boom')));
    let seen: OurHours | null | undefined = ourHours;
    service.summary$.subscribe((s) => (seen = s));

    expect(() => service.reload()).not.toThrow();
    expect(seen).toBeNull();
  });

  it('clears the held summary', () => {
    service.reload();
    service.clear();

    expect(service.current).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-summary.service.spec.ts`
Expected: FAIL — `./hours-summary.service` existiert nicht.

- [ ] **Step 3: Write minimal implementation**

Create `frontend/src/app/shared/services/hours-summary.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { HourEntryService } from './hour-entry.service';
import { OurHours } from '../models/hour-entry.model';

/**
 * Hält die Stundenübersicht der eigenen Familie für das jüngste Semester als
 * gemeinsamen Zustand: der Header-Ring und die Seite "Unsere Stunden" lesen
 * dieselbe Quelle, damit der Ring nach dem Erfassen sofort stimmt.
 */
@Injectable({ providedIn: 'root' })
export class HoursSummaryService {
  private subject = new BehaviorSubject<OurHours | null>(null);
  summary$: Observable<OurHours | null> = this.subject.asObservable();

  constructor(private hourService: HourEntryService) {}

  get current(): OurHours | null {
    return this.subject.value;
  }

  /** Lädt neu; Fehler bleiben still, die Anzeige verschwindet dann einfach. */
  reload(): void {
    this.hourService.our('').subscribe({
      next: (summary) => this.subject.next(summary),
      error: () => this.subject.next(null),
    });
  }

  clear(): void {
    this.subject.next(null);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-summary.service.spec.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/shared/services/hours-summary.service.ts frontend/src/app/shared/services/hours-summary.service.spec.ts
git commit -m "feat(fe): HoursSummaryService als gemeinsame Stundenquelle"
```

---

### Task 3: Komponente `HoursRingComponent`

Anzeige-Komponente: Inline-SVG-Ring, Prozent in der Mitte, Ist/Soll daneben, Tooltip, Link auf `/stunden`.

**Files:**
- Create: `frontend/src/app/shared/components/hours-ring/hours-ring.component.ts`
- Create: `frontend/src/app/shared/components/hours-ring/hours-ring.component.html`
- Create: `frontend/src/app/shared/components/hours-ring/hours-ring.component.scss`
- Test: `frontend/src/app/shared/components/hours-ring/hours-ring.component.spec.ts`

**Interfaces:**
- Consumes: `HoursSummaryService.summary$` (Task 2), `buildRingState`, `currentYearMonth`, `RingState` (Task 1), `formatMinutes` aus `src/app/shared/util/time-format.util.ts`.
- Produces: `class HoursRingComponent` mit Selektor `app-hours-ring`, öffentlichen Feldern `state: RingState | null`, `radius`, `circumference` und Getter `dashOffset: number`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.component.spec.ts`:

```ts
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
    .map((month) => ({ month, sollMinutes: 300, istMinutes: 0 }));
  return {
    familyId: 'fam-1',
    familyMonthlyMinutes: 300,
    monthsInSemester: 6,
    sollMinutes: 1800,
    istMinutes: 750,
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

  it('marks the arc with the calculated status', () => {
    subject.next(ourHours({ istMinutes: 1800 }));
    fixture.detectChanges();

    const arc = fixture.nativeElement.querySelector('.ring-arc') as SVGElement;
    expect(arc.classList).toContain('status-done');
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.component.spec.ts`
Expected: FAIL — `./hours-ring.component` existiert nicht.

- [ ] **Step 3: Write the component class**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.component.ts`:

```ts
import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { HoursSummaryService } from '../../services/hours-summary.service';
import { formatMinutes } from '../../util/time-format.util';
import { RingState, buildRingState, currentYearMonth } from './hours-ring.util';

@Component({
  selector: 'app-hours-ring',
  standalone: true,
  imports: [CommonModule, RouterModule, MatTooltipModule],
  templateUrl: './hours-ring.component.html',
  styleUrl: './hours-ring.component.scss',
})
export class HoursRingComponent implements OnInit, OnDestroy {
  state: RingState | null = null;

  /** Passt in die viewBox 40×40 bei Strichbreite 4. */
  readonly radius = 16;
  readonly circumference = 2 * Math.PI * 16;

  private sub?: Subscription;

  constructor(private summary: HoursSummaryService) {}

  ngOnInit(): void {
    this.sub = this.summary.summary$.subscribe((our) => {
      this.state = buildRingState(our, currentYearMonth(new Date()));
    });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  /** Ungezeichneter Rest des Kreises: 0 = voll. */
  get dashOffset(): number {
    const percent = this.state?.ringPercent ?? 0;
    return this.circumference * (1 - percent / 100);
  }

  formatMinutes = formatMinutes;
}
```

- [ ] **Step 4: Write the template**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.component.html`:

```html
@if (state) {
  <a class="hours-ring" routerLink="/stunden"
     [matTooltip]="state.tooltip" matTooltipClass="hours-ring-tooltip"
     [attr.aria-label]="state.ariaLabel">
    <svg class="ring" viewBox="0 0 40 40" width="40" height="40" aria-hidden="true">
      <circle class="ring-track" cx="20" cy="20" [attr.r]="radius"></circle>
      <circle class="ring-arc" [ngClass]="'status-' + state.status"
              cx="20" cy="20" [attr.r]="radius"
              [attr.stroke-dasharray]="circumference"
              [attr.stroke-dashoffset]="dashOffset"
              transform="rotate(-90 20 20)"></circle>
      <text class="ring-label" x="20" y="20"
            text-anchor="middle" dominant-baseline="central">{{ state.realPercent }}%</text>
    </svg>
    <span class="hours-ring-text">
      <span class="hours-ring-done">{{ formatMinutes(state.istMinutes) }} h</span>
      <span class="hours-ring-goal">von {{ formatMinutes(state.sollMinutes) }} h</span>
    </span>
  </a>
}
```

- [ ] **Step 5: Write the styles**

Create `frontend/src/app/shared/components/hours-ring/hours-ring.component.scss`.
Die Farben sind helle Töne, weil die Toolbar `color="primary"` einen dunklen
Hintergrund hat. Der Einlauf-Effekt entsteht ohne JavaScript: die Keyframe gibt
nur den Startwert vor, das Ziel ist der am Element gesetzte `stroke-dashoffset`.

```scss
.hours-ring {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-right: 16px;
  color: inherit;
  text-decoration: none;
  cursor: pointer;

  &:hover .ring-arc,
  &:focus-visible .ring-arc {
    filter: brightness(1.15);
  }
}

.ring {
  flex: 0 0 auto;
}

.ring-track {
  fill: none;
  stroke: rgba(255, 255, 255, 0.25);
  stroke-width: 4;
}

.ring-arc {
  fill: none;
  stroke-width: 4;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.4s ease-out;
  animation: hours-ring-in 0.8s ease-out;

  &.status-done,
  &.status-onTrack {
    stroke: #66bb6a;
  }

  &.status-slightlyBehind {
    stroke: #ffca28;
  }

  &.status-behind {
    stroke: #ef5350;
  }
}

@keyframes hours-ring-in {
  from {
    stroke-dashoffset: 100.53px;   // voller Umfang bei r = 16
  }
}

.ring-label {
  font-size: 11px;
  font-weight: 600;
  fill: currentColor;
}

.hours-ring-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}

.hours-ring-done {
  font-size: 13px;
  font-weight: 600;
}

.hours-ring-goal {
  font-size: 11px;
  opacity: 0.75;
}

::ng-deep .hours-ring-tooltip {
  white-space: pre-line;
  font-size: 12px;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/hours-ring.component.spec.ts`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/shared/components/hours-ring/
git commit -m "feat(fe): Stunden-Fortschrittsring als Header-Komponente"
```

---

### Task 4: Ring in die Toolbar einbauen und beim Login laden

Der Ring erscheint links vom Benutzernamen; die Daten werden geladen, sobald der angemeldete Benutzer bekannt ist.

**Files:**
- Modify: `frontend/src/app/app.component.ts` (Imports, Konstruktor, `ngOnInit`)
- Modify: `frontend/src/app/app.component.html:81-86` (Toolbar-Bereich)
- Test: `frontend/src/app/app.component.spec.ts` (neuer Stub-Provider + neue Specs)

**Interfaces:**
- Consumes: `HoursRingComponent` (Task 3), `HoursSummaryService.reload()` (Task 2), bestehende `AuthService.tokenReceived$` und `CurrentUserService.loadCurrentUser()`.
- Produces: nichts für spätere Tasks.

- [ ] **Step 1: Write the failing test**

In `frontend/src/app/app.component.spec.ts` einen Stub für
`HoursSummaryService` ergänzen und zwei Specs anhängen. Der Stub ist nötig,
damit der Test ohne `HttpClient` läuft — die echte Kette wäre
`HoursSummaryService → HourEntryService → ApiService → HttpClient`.

Wichtig: die bestehenden Specs rufen bewusst kein `detectChanges()`, weil der
`OAuthService`-Spy im `beforeEach` kein `events` hat und `ngOnInit` über
`auth.tokenReceived$` darauf zugreift. Die neuen Specs überschreiben den
Provider deshalb selbst mit `events: of()` und `hasValidAccessToken: true`.

Imports ergänzen:

```ts
import { HoursSummaryService } from './shared/services/hours-summary.service';
```

Oben in der Suite deklarieren:

```ts
  let hoursSummaryStub: { summary$: unknown; reload: jasmine.Spy; clear: jasmine.Spy };
```

Im `beforeEach` vor `TestBed.configureTestingModule` zuweisen:

```ts
    hoursSummaryStub = {
      summary$: of(null),
      reload: jasmine.createSpy('reload'),
      clear: jasmine.createSpy('clear'),
    };
```

und in die `providers` aufnehmen:

```ts
        { provide: HoursSummaryService, useValue: hoursSummaryStub },
```

Neue Specs am Ende der Suite:

```ts
  /** Gemeinsame Vorbereitung: angemeldeter Benutzer mit Familie, ngOnInit lauffähig. */
  function authenticatedFixture() {
    TestBed.overrideProvider(OAuthService, {
      useValue: {
        ...jasmine.createSpyObj('OAuthService', ['configure', 'setupAutomaticSilentRefresh']),
        hasValidAccessToken: () => true,
        getAccessToken: () => 'token',
        getIdentityClaims: () => ({ preferred_username: 'elternteil' }),
        loadDiscoveryDocumentAndTryLogin: () => Promise.resolve(true),
        events: of(),
      },
    });
    TestBed.overrideProvider(CurrentUserService, {
      useValue: { isAdmin: false, loadCurrentUser: () => of({ id: 'p1' }) },
    });

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('loads the hours summary once the current user is known', () => {
    authenticatedFixture();

    expect(hoursSummaryStub.reload).toHaveBeenCalled();
  });

  it('renders the hours ring in the toolbar', () => {
    const fixture = authenticatedFixture();

    expect(fixture.nativeElement.querySelector('app-hours-ring')).not.toBeNull();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/app.component.spec.ts`
Expected: FAIL — `reload` wird nicht gerufen und `app-hours-ring` fehlt im DOM.

- [ ] **Step 3: Wire the component into the toolbar**

In `frontend/src/app/app.component.ts`:

Import ergänzen:

```ts
import { HoursRingComponent } from './shared/components/hours-ring/hours-ring.component';
import { HoursSummaryService } from './shared/services/hours-summary.service';
```

`imports`-Array um `HoursRingComponent` erweitern:

```ts
  imports: [
    CommonModule, RouterModule,
    MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule,
    HoursRingComponent,
  ],
```

Konstruktor und `ngOnInit` ersetzen:

```ts
  constructor(
    public auth: AuthService,
    public currentUser: CurrentUserService,
    private hoursSummary: HoursSummaryService,
  ) {}

  toggleAdminSection(): void {
    this.adminSectionExpanded = !this.adminSectionExpanded;
  }

  ngOnInit(): void {
    // Always attempt to load — works in dev mode (no OIDC) and after production login
    this.loadUserAndHours();
    // After Keycloak redirect login the token arrives asynchronously — reload user then too
    this.auth.tokenReceived$.subscribe(() => this.loadUserAndHours());
  }

  private loadUserAndHours(): void {
    this.currentUser.loadCurrentUser().subscribe({
      next: () => this.hoursSummary.reload(),
      error: () => {},
    });
  }
```

In `frontend/src/app/app.component.html` den Toolbar-Block ersetzen:

```html
      @if (auth.isAuthenticated) {
        <app-hours-ring></app-hours-ring>
        <span>{{ auth.userName }}</span>
        <button mat-icon-button (click)="auth.logout()">
          <mat-icon>logout</mat-icon>
        </button>
      }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/app.component.spec.ts`
Expected: PASS, auch die bestehenden Admin-Section-Specs.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/app.component.ts frontend/src/app/app.component.html frontend/src/app/app.component.spec.ts
git commit -m "feat(fe): Stunden-Ring in der Toolbar neben dem Benutzernamen"
```

---

### Task 5: „Unsere Stunden“ liest denselben Zustand

Die Seite verliert ihren eigenen `our`-Fetch und arbeitet über `HoursSummaryService`; nach Speichern und Löschen wird neu geladen, wodurch der Header sofort stimmt.

**Files:**
- Modify: `frontend/src/app/stunden/stunden.component.ts:44` (Feld bleibt), `:53-80` (Konstruktor, `ngOnInit`, `loadOur`), `:164-187` (`save`, `delete`)
- Test: `frontend/src/app/stunden/stunden.component.spec.ts` (neue Specs; bestehende bleiben unverändert gültig, weil `HoursSummaryService` echt bleibt und den bereits gestubbten `HourEntryService` benutzt)

**Interfaces:**
- Consumes: `HoursSummaryService.summary$` und `.reload()` (Task 2).
- Produces: nichts für spätere Tasks.

- [ ] **Step 1: Write the failing test**

Am Ende der Suite in `frontend/src/app/stunden/stunden.component.spec.ts`
anhängen (der Import von `HoursSummaryService` wird oben ergänzt:
`import { HoursSummaryService } from '../shared/services/hours-summary.service';`):

```ts
  it('takes the family hours from the shared summary service', () => {
    const summary = TestBed.inject(HoursSummaryService);

    expect(component.our).toEqual(summary.current);
  });

  it('reloads the shared summary after saving an entry', () => {
    const summary = TestBed.inject(HoursSummaryService);
    const reload = spyOn(summary, 'reload').and.callThrough();

    component.newEntry();
    component.form.setValue({
      roleKey: '__kochen__',
      date: new Date(2026, 8, 15),
      time: '01:30',
      comment: '',
    });
    service.create.and.returnValue(of(entry));
    component.save();

    expect(reload).toHaveBeenCalled();
  });

  it('reloads the shared summary after deleting an entry', () => {
    const summary = TestBed.inject(HoursSummaryService);
    const reload = spyOn(summary, 'reload').and.callThrough();
    service.delete.and.returnValue(of(undefined));

    component.delete(entry);

    expect(reload).toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/stunden.component.spec.ts`
Expected: FAIL — `reload` wird nicht gerufen, `summary.current` ist `null` während `component.our` gesetzt ist.

- [ ] **Step 3: Rewire the component**

In `frontend/src/app/stunden/stunden.component.ts`:

Import ergänzen:

```ts
import { HoursSummaryService } from '../shared/services/hours-summary.service';
```

Klasse implementiert zusätzlich `OnDestroy`:

```ts
import { Component, OnDestroy, OnInit } from '@angular/core';
import { Subscription } from 'rxjs';
...
export class StundenComponent implements OnInit, OnDestroy {
```

Konstruktor erweitern:

```ts
  constructor(
    private hourService: HourEntryService,
    private notify: NotificationService,
    public currentUser: CurrentUserService,
    private hoursSummary: HoursSummaryService,
  ) {}
```

Feld für das Abo ergänzen (neben `our`):

```ts
  private summarySub?: Subscription;
```

`ngOnInit` und `loadOur` ersetzen:

```ts
  ngOnInit(): void {
    this.load();
    this.summarySub = this.hoursSummary.summary$.subscribe((o) => (this.our = o));
    this.hoursSummary.reload();
    this.hourService.roleOptions().subscribe({
      next: (opts) => (this.options = opts),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  ngOnDestroy(): void {
    this.summarySub?.unsubscribe();
  }
```

Die Methode `loadOur()` entfällt vollständig. In `save()` und `delete()` die
Aufrufe `this.loadOur();` durch `this.hoursSummary.reload();` ersetzen.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test -- --watch=false --browsers=ChromeHeadless --include=**/stunden.component.spec.ts`
Expected: PASS, inklusive der bestehenden Specs `loads family hours and groups entries by month` und `monthIsNegative`.

- [ ] **Step 5: Run the whole frontend suite and build**

Run: `npm test -- --watch=false --browsers=ChromeHeadless`
Expected: keine neuen Fehlschläge gegenüber dem Stand vor diesem Plan.

Run: `npm run build`
Expected: erfolgreicher Build.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/stunden/stunden.component.ts frontend/src/app/stunden/stunden.component.spec.ts
git commit -m "refactor(fe): Unsere Stunden nutzt gemeinsamen HoursSummaryService"
```

---

## Manueller Smoke-Test (nach Task 5)

1. App starten (`start-dev.bat`), als Elternteil mit Familie anmelden.
2. Header prüfen: Ring links vom Benutzernamen, Prozent in der Mitte, `HH:MM h`-Werte daneben, Einlauf-Animation beim ersten Laden.
3. Mit der Maus über den Ring: Tooltip mit fünf Zeilen, Werte passen zur Seite „Unsere Stunden“.
4. Auf den Ring klicken: Wechsel auf `/stunden`.
5. Dort einen Eintrag erfassen: Ring im Header aktualisiert sich sofort, ohne Neuladen. Eintrag wieder löschen: Ring geht zurück.
6. Als Admin ohne Familie oder in einem Semester ohne konfiguriertes Soll anmelden: kein Ring, kein leerer Kreis, keine Fehlermeldung.
