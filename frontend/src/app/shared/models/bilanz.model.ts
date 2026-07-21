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
