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

  it('shows no Soll due yet when today lies before the semester starts', () => {
    const state = buildRingState(ourHours({ istMinutes: 0 }), '2026-08')!;

    expect(state.sollToDateMinutes).toBe(0);
    expect(state.status).toBe('onTrack');
    expect(state.ringPercent).toBe(0);
  });

  it('uses only the months carrying Soll as denominator on an aliquoted semester', () => {
    const our = ourHours({ istMinutes: 360 });
    our.months = [
      { month: '2026-09', sollMinutes: 0, istMinutes: 0 },
      { month: '2026-10', sollMinutes: 0, istMinutes: 0 },
      { month: '2026-11', sollMinutes: 0, istMinutes: 0 },
      { month: '2026-12', sollMinutes: 300, istMinutes: 0 },
      { month: '2027-01', sollMinutes: 300, istMinutes: 0 },
      { month: '2027-02', sollMinutes: 300, istMinutes: 360 },
    ];
    our.sollMinutes = 900;
    our.monthsInSemester = 6;

    const state = buildRingState(our, '2027-01')!;

    expect(state.totalMonths).toBe(3);
    expect(state.monthlySollMinutes).toBe(300);
    expect(state.tooltip).toContain('Soll gesamt: 15:00 h (3 Monate × 05:00 h)');
    expect(state.tooltip).toContain('Fällig bis heute: 10:00 h (2 von 3 Monaten)');
    // 4:00 h Rückstand liegt unter dem echten Monats-Soll (05:00 h) ⇒ leicht im Rückstand,
    // nicht "im Rückstand" wie bei der alten Rechnung mit monthsInSemester (900/6 = 02:30 h).
    expect(state.deltaMinutes).toBe(-240);
    expect(state.status).toBe('slightlyBehind');
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
