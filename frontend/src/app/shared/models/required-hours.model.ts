export interface RequiredHoursTier {
  fromChild: number;
  minutesPerMonth: number;
}

export interface RequiredHours {
  semesterId: string;
  defaultMinutesPerMonth: number;
  tiers: RequiredHoursTier[];
}
