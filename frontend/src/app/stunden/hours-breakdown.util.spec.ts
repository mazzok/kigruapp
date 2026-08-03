import { OurHoursMonthRow } from '../shared/models/hour-entry.model';
import { formatMonthLabel, summarizeMonths } from './hours-breakdown.util';

describe('hours-breakdown.util', () => {
  function share(childId: string, minutes: number, fractionPercent = 100, discountPercent = 0) {
    return { childId, minutes, fractionPercent, discountPercent };
  }

  function row(month: string, sollMinutes: number, shares = [share('a', sollMinutes)]): OurHoursMonthRow {
    return { month, sollMinutes, istMinutes: 0, children: shares };
  }

  it('formatiert Monatsbezeichnungen deutsch', () => {
    expect(formatMonthLabel('2026-11')).toBe('Nov 2026');
    expect(formatMonthLabel('2027-01')).toBe('Jän 2027');
  });

  it('fasst aufeinanderfolgende Monate mit gleichen Werten zusammen', () => {
    const spans = summarizeMonths([
      row('2026-09', 480), row('2026-10', 480), row('2026-11', 480),
    ]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Sep 2026 – Nov 2026');
    expect(spans[0].months).toEqual(['2026-09', '2026-10', '2026-11']);
    expect(spans[0].sollMinutes).toBe(480);
  });

  it('trennt bei abweichenden Werten', () => {
    const spans = summarizeMonths([
      row('2026-09', 480),
      row('2026-10', 593, [share('a', 480), share('b', 113, 50, 25)]),
      row('2026-11', 705, [share('a', 480), share('b', 225, 100, 25)]),
      row('2026-12', 705, [share('a', 480), share('b', 225, 100, 25)]),
    ]);

    expect(spans.map((s) => s.label)).toEqual([
      'Sep 2026', 'Okt 2026', 'Nov 2026 – Dez 2026',
    ]);
  });

  it('lässt Monate ohne Soll weg', () => {
    const spans = summarizeMonths([
      { month: '2026-09', sollMinutes: 0, istMinutes: 120, children: [] },
      row('2026-10', 480),
    ]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Okt 2026');
  });

  it('fasst über den Jahreswechsel zusammen', () => {
    const spans = summarizeMonths([row('2026-12', 480), row('2027-01', 480)]);

    expect(spans.length).toBe(1);
    expect(spans[0].label).toBe('Dez 2026 – Jän 2027');
  });

  it('trennt bei einer Lücke in der Monatsfolge', () => {
    const spans = summarizeMonths([row('2026-09', 480), row('2026-11', 480)]);

    expect(spans.map((s) => s.label)).toEqual(['Sep 2026', 'Nov 2026']);
  });
});
