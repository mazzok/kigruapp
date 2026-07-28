import { RequiredHoursTier } from '../../shared/models/required-hours.model';

function rateForChild(defaultMinutes: number, tiers: RequiredHoursTier[], n: number): number {
  let rate = defaultMinutes;
  let bestFrom = 0;
  for (const t of tiers) {
    if (t.fromChild <= n && t.fromChild >= bestFrom) {
      bestFrom = t.fromChild;
      rate = t.minutesPerMonth;
    }
  }
  return rate;
}

export function familyMonthlyMinutes(
  cfg: { defaultMinutesPerMonth: number; tiers: RequiredHoursTier[] },
  childCount: number,
): number {
  let total = 0;
  for (let n = 1; n <= childCount; n++) {
    total += rateForChild(cfg.defaultMinutesPerMonth, cfg.tiers, n);
  }
  return total;
}
