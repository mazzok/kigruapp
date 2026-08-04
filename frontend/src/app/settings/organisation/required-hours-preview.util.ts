import { RequiredHoursOrder, RequiredHoursTier } from '../../shared/models/required-hours.model';

/** Rabatt der höchsten passenden Staffel für einen 1-basierten Rang. */
function discountPercent(tiers: RequiredHoursTier[], rank: number): number {
  let bestFrom = 0;
  let percent = 0;
  for (const t of tiers) {
    if (t.fromChild <= rank && t.fromChild >= bestFrom) {
      bestFrom = t.fromChild;
      percent = t.percent;
    }
  }
  return percent;
}

export function familyMonthlyMinutes(
  cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] },
  childCount: number,
): number {
  let total = 0;
  for (let n = 1; n <= childCount; n++) {
    total += Math.round(cfg.defaultMinutesPerMonth * (100 - discountPercent(cfg.tiers, n)) / 100);
  }
  return total;
}

/** Monatswert einer Kombination von Gruppensätzen unter der gewählten Reihenfolge. */
export function groupCombinationMinutes(
  rates: number[],
  tiers: RequiredHoursTier[],
  order: RequiredHoursOrder,
): number {
  const sorted = [...rates].sort((a, b) => (order === 'LEAST_EXPENSIVE_FIRST' ? a - b : b - a));
  return sorted.reduce(
    (total, rate, index) => total + Math.round(rate * (100 - discountPercent(tiers, index + 1)) / 100),
    0,
  );
}
