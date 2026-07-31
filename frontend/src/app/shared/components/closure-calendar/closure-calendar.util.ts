import { ClosureDefinition, ClosurePeriod, Holiday } from '../../models/closure.model';

export interface CalendarDay {
  /** ISO yyyy-MM-dd */
  date: string;
  dayOfMonth: number;
  /** Werktag und kein Feiertag — nur solche Tage lassen sich markieren. */
  selectable: boolean;
  holidayName: string | null;
  /** Eine Farbe je zugeordneter Definition, in Reihenfolge der Definitionsliste. */
  colors: string[];
  labels: string[];
}

export interface CalendarMonth {
  /** yyyy-MM, stabil fuer @for-Tracking */
  key: string;
  label: string;
  /** Leerzellen vor dem ersten gerenderten Tag, damit die Spalte stimmt. */
  leadingBlanks: number;
  days: CalendarDay[];
}

const MONTH_NAMES = [
  'Jänner', 'Februar', 'März', 'April', 'Mai', 'Juni',
  'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember',
];

/**
 * Ohne den Zeitanteil liest der Browser den String als UTC und verschiebt den
 * Tag je nach Zone. Gleiches Muster wie in cooking.component.ts.
 */
function parseIso(iso: string): Date {
  return new Date(`${iso}T00:00:00`);
}

function toIso(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function isWeekend(iso: string): boolean {
  const weekday = parseIso(iso).getDay();
  return weekday === 0 || weekday === 6;
}

/** Montag = 0 … Sonntag = 6, passend zur Spaltenreihenfolge des Rasters. */
function mondayBasedWeekday(iso: string): number {
  return (parseIso(iso).getDay() + 6) % 7;
}

export function buildMonths(
  from: string,
  to: string,
  periods: ClosurePeriod[],
  definitions: ClosureDefinition[],
  holidays: Holiday[],
): CalendarMonth[] {
  if (!from || !to || from > to) {
    return [];
  }

  const holidayNames = new Map(holidays.map(h => [h.date, h.name]));
  const definitionById = new Map(definitions.map(d => [d.id, d]));

  // Reihenfolge der Definitionsliste bestimmt die Segmentreihenfolge im Tag,
  // damit die Aufteilung ueber alle Tage hinweg gleich aussieht.
  const order = new Map(definitions.map((d, index) => [d.id, index]));
  const sortedPeriods = [...periods].sort(
    (a, b) => (order.get(a.definitionId) ?? Number.MAX_SAFE_INTEGER)
            - (order.get(b.definitionId) ?? Number.MAX_SAFE_INTEGER),
  );

  const months: CalendarMonth[] = [];
  let current: CalendarMonth | null = null;

  for (let cursor = parseIso(from); toIso(cursor) <= to; cursor.setDate(cursor.getDate() + 1)) {
    const iso = toIso(cursor);
    const key = iso.slice(0, 7);

    if (!current || current.key !== key) {
      current = {
        key,
        label: `${MONTH_NAMES[cursor.getMonth()]} ${cursor.getFullYear()}`,
        leadingBlanks: mondayBasedWeekday(iso),
        days: [],
      };
      months.push(current);
    }

    const holidayName = holidayNames.get(iso) ?? null;
    const colors: string[] = [];
    const labels: string[] = [];

    for (const period of sortedPeriods) {
      if (iso < period.from || iso > period.to) {
        continue;
      }
      const definition = definitionById.get(period.definitionId);
      if (!definition) {
        continue;
      }
      colors.push(definition.color);
      labels.push(definition.label);
    }

    current.days.push({
      date: iso,
      dayOfMonth: cursor.getDate(),
      selectable: !isWeekend(iso) && holidayName === null,
      holidayName,
      colors,
      labels,
    });
  }

  return months;
}

/** Mehrfachzuordnung teilt den Tag in gleich breite Segmente. */
export function dayBackground(day: CalendarDay): string {
  if (day.colors.length === 0) {
    return '';
  }
  if (day.colors.length === 1) {
    return day.colors[0];
  }
  const step = 100 / day.colors.length;
  const stops = day.colors
    .map((color, index) => {
      const start = index === 0 ? '0%' : `${(index * step).toFixed(3).replace(/\.?0+$/, '')}%`;
      const end = index === day.colors.length - 1
        ? '100%'
        : `${((index + 1) * step).toFixed(3).replace(/\.?0+$/, '')}%`;
      return `${color} ${start} ${end}`;
    })
    .join(', ');
  return `linear-gradient(90deg, ${stops})`;
}
