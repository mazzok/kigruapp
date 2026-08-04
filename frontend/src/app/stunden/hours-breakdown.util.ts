import { OurHoursChildShare, OurHoursMonthRow } from '../shared/models/hour-entry.model';

export interface MonthSpan {
  label: string;
  months: string[];
  sollMinutes: number;
  shares: OurHoursChildShare[];
}

const MONTH_NAMES = [
  'Jän', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun',
  'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez',
];

export function formatMonthLabel(month: string): string {
  const [year, monthPart] = month.split('-');
  const index = Number(monthPart) - 1;
  return `${MONTH_NAMES[index] ?? monthPart} ${year}`;
}

/** Vergleichsschlüssel einer Monatszeile: gleiche Aufteilung ergibt denselben Schlüssel. */
function signature(row: OurHoursMonthRow): string {
  const shares = [...row.children]
    .sort((a, b) => a.childId.localeCompare(b.childId))
    .map((s) => `${s.childId}:${s.minutes}:${s.fractionPercent}:${s.discountPercent}`)
    .join('|');
  return `${row.sollMinutes}#${shares}`;
}

/** Fortlaufender Monat nach "YYYY-MM". */
function nextMonth(month: string): string {
  const [year, monthPart] = month.split('-').map(Number);
  return monthPart === 12
    ? `${year + 1}-01`
    : `${year}-${String(monthPart + 1).padStart(2, '0')}`;
}

/**
 * Monate mit Soll, benachbarte Monate mit identischer Aufteilung zu einer Spanne
 * zusammengefasst. Monate ohne Soll entfallen.
 */
export function summarizeMonths(months: OurHoursMonthRow[]): MonthSpan[] {
  const spans: MonthSpan[] = [];
  let previous: OurHoursMonthRow | null = null;

  for (const row of months) {
    if (row.sollMinutes <= 0) {
      previous = null;
      continue;
    }
    const continues = previous !== null
      && signature(previous) === signature(row)
      && nextMonth(previous.month) === row.month;

    if (continues) {
      const span = spans[spans.length - 1];
      span.months.push(row.month);
      span.label = `${formatMonthLabel(span.months[0])} – ${formatMonthLabel(row.month)}`;
    } else {
      spans.push({
        label: formatMonthLabel(row.month),
        months: [row.month],
        sollMinutes: row.sollMinutes,
        shares: row.children,
      });
    }
    previous = row;
  }
  return spans;
}
