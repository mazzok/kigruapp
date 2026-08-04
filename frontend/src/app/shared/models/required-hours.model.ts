export type RequiredHoursOrder = 'MOST_EXPENSIVE_FIRST' | 'LEAST_EXPENSIVE_FIRST';

export interface RequiredHoursTier {
  fromChild: number;
  percent: number;
}

export interface RequiredHoursGroupRate {
  groupInstanceId: string;
  minutesPerMonth: number;
}

export interface RequiredHours {
  semesterId: string;
  defaultMinutesPerMonth: number;
  allGroups: boolean;
  order: RequiredHoursOrder;
  groupRates: RequiredHoursGroupRate[];
  tiers: RequiredHoursTier[];
}
