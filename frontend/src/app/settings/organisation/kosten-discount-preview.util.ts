export interface KostenDiscountTier {
  fromChild: number;
  percent: number;
}

export function discountFactors(tiers: KostenDiscountTier[], maxChildren: number): { child: number; percent: number }[] {
  const sorted = [...tiers].sort((a, b) => a.fromChild - b.fromChild);
  const rows: { child: number; percent: number }[] = [];
  for (let n = 1; n <= maxChildren; n++) {
    let percent = 0;
    let bestFrom = 0;
    for (const t of sorted) {
      if (t.fromChild <= n && t.fromChild >= bestFrom) {
        bestFrom = t.fromChild;
        percent = t.percent;
      }
    }
    rows.push({ child: n, percent });
  }
  return rows;
}
