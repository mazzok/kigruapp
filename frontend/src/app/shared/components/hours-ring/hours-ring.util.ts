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
