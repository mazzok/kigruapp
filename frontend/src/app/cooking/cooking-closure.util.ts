import { ClosurePeriod, Holiday } from '../shared/models/closure.model';

/** ISO-Datum ohne Zeitzonenumrechnung, gleiches Muster wie im Kalenderraster. */
export function toIsoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Alle Tage, an denen der Kindergarten geschlossen hat: erfasste Zeitraeume
 * plus gesetzliche Feiertage. Feiertage zaehlen mit, obwohl sie nicht
 * persistiert sind — sonst waere ein Kochdienst am 25. Dezember erlaubt.
 */
export function closedDatesFrom(periods: ClosurePeriod[], holidays: Holiday[]): Set<string> {
  const closed = new Set<string>();

  for (const period of periods) {
    for (
      let cursor = new Date(`${period.from}T00:00:00`);
      toIsoDate(cursor) <= period.to;
      cursor.setDate(cursor.getDate() + 1)
    ) {
      closed.add(toIsoDate(cursor));
    }
  }

  for (const holiday of holidays) {
    closed.add(holiday.date);
  }

  return closed;
}
