export interface BilanzLineBreakdown {
  label: string;
  currencySymbol: string;
  baseAmount: number;
  discountPercent: number;
  discountOrdinal: number;
  presentDays: number;
  daysInMonth: number;
  fullMonth: boolean;
  overridden: boolean;
  effectiveAmount: number;
}

export interface BilanzMonthCell {
  month: number;
  amount: number;
  currencySymbol: string;
  mixedCurrency: boolean;
  future: boolean;
  editable: boolean;
  active: boolean;
  entryMarker: boolean;
  exitMarker: boolean;
  reason: string | null;          // "FUTURE" | "NO_PLACE" | null
  aliquotMode: string | null;     // "NONE" | "WHOLE_MONTH" | "PER_DAY" | null
  entryDate: string | null;
  exitDate: string | null;
  lines: BilanzLineBreakdown[];
}

export interface BilanzChildRow {
  personId: string;
  name: string;
  months: BilanzMonthCell[];
  total: number;
}

export interface BilanzMatrix {
  year: number;
  currentYearMonth: string;
  children: BilanzChildRow[];
}

export interface BilanzCellLine {
  personId: string;
  childName: string;
  definitionId: string;
  label: string;
  currencySymbol: string;
  defaultAmount: number;
  effectiveAmount: number;
}

export interface BilanzCell {
  lines: BilanzCellLine[];
  sum: number;
  mixedCurrency: boolean;
}

export interface UpsertOverrideRequest {
  personId: string;
  year: number;
  month: number;
  definitionId: string;
  amount: number;
}
