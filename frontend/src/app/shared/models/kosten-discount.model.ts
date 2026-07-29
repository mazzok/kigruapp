export type KostenDiscountOrder = 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST';

export interface KostenDiscountTier {
  fromChild: number;
  percent: number;
}

export interface KostenDiscount {
  semesterId: string;
  applyToAll: boolean;
  order: KostenDiscountOrder;
  tiers: KostenDiscountTier[];
}
