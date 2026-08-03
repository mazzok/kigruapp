import { OurHours } from '../../models/hour-entry.model';
import { formatMinutes } from '../../util/time-format.util';

export type RingStatus = 'done' | 'onTrack' | 'slightlyBehind' | 'behind';
export type RingLevel = 'level1' | 'level2' | 'level3' | 'level4' | 'level5';

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
  fulfillmentPercent: number;
  level: RingLevel;
  tooltip: string;
  ariaLabel: string;
}

const STATUS_TEXT: Record<RingStatus, string> = {
  done: 'Soll erfüllt',
  onTrack: 'im Plan',
  slightlyBehind: 'leicht im Rückstand',
  behind: 'im Rückstand',
};

/** Fünf Stufen à 20 Prozentpunkte: level1 dunkelrot bis level5 grün. */
export function ringLevel(fulfillmentPercent: number): RingLevel {
  if (fulfillmentPercent < 20) return 'level1';
  if (fulfillmentPercent < 40) return 'level2';
  if (fulfillmentPercent < 60) return 'level3';
  if (fulfillmentPercent < 80) return 'level4';
  return 'level5';
}

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
  const totalMonths = monthsWithSoll.length > 0 ? monthsWithSoll.length : our.monthsInSemester;

  const istMinutes = our.istMinutes;
  const sollMinutes = our.sollMinutes;
  const deltaMinutes = istMinutes - sollToDateMinutes;

  // Vor dem ersten fälligen Monat ist nichts offen — dann gilt der Ring als erfüllt.
  const fulfillmentPercent = sollToDateMinutes <= 0
    ? 100
    : Math.min(100, Math.round((istMinutes / sollToDateMinutes) * 100));
  const level = ringLevel(fulfillmentPercent);
  // Abgeleitet statt familyMonthlyMinutes: Bei aliquotierten Semestern (unterjähriger
  // Ein- oder Austritt) liefert das Backend für Monate, die die Familie nicht schuldet,
  // sollMinutes = 0. Nur die Monate mit Soll zu zählen hält monatlichesSoll, den
  // Tooltip-Bruch und das Amber/Rot-Toleranzband konsistent mit dem angezeigten Soll.
  // familyMonthlyMinutes bleibt weiterhin bewusst ungenutzt.
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
    `Farbe: ${fulfillmentPercent} % des bis heute Fälligen geleistet — ab 80 % grün, darunter gelb bis rot.`,
  ].join('\n');

  const ariaLabel = `Stunden der Familie: ${formatMinutes(istMinutes)} h von `
    + `${formatMinutes(sollMinutes)} h geleistet, ${STATUS_TEXT[status]}`;

  return {
    ringPercent, realPercent, status,
    istMinutes, sollMinutes, sollToDateMinutes, deltaMinutes,
    elapsedMonths, totalMonths, monthlySollMinutes, avgDoneMinutes,
    fulfillmentPercent, level,
    tooltip, ariaLabel,
  };
}
