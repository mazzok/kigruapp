export interface HourEntry {
  id: string;
  personId: string;
  semesterId: string;
  roleFieldInstanceId: string | null; // null = Kochen
  roleLabel: string;
  date: string;   // YYYY-MM-DD
  minutes: number;
  comment: string;
}

export interface RoleOption {
  fieldInstanceId: string | null; // null = Kochen
  definitionId: string | null;
  label: string;
}

export interface SaveHourEntryRequest {
  roleFieldInstanceId: string | null;
  date: string;
  minutes: number;
  comment: string;
}

export interface HourSummary {
  personId: string;
  name: string;
  totalMinutes: number;
  entries: HourEntry[];
}

export interface FamilyHoursSummary {
  familyId: string;
  familyName: string;
  childCount: number;
  familyMonthlyMinutes: number;
  monthsInSemester: number;
  sollMinutes: number;
  istMinutes: number;
  members: HourSummary[];
}

export interface OurHoursChild {
  childId: string;
  name: string;
  groupLabel: string | null;
  groupColor: string | null;
  baseMinutesPerMonth: number;
  entryDate: string | null;
  exitDate: string | null;
  sollMinutes: number;
}

export interface OurHoursChildShare {
  childId: string;
  minutes: number;
  fractionPercent: number;
  discountPercent: number;
}

export interface OurHoursMonthRow {
  month: string;        // "YYYY-MM"
  sollMinutes: number;
  istMinutes: number;
  children: OurHoursChildShare[];
}

export interface OurHoursEntry {
  id: string;
  personId: string;
  personName: string;
  roleLabel: string;
  date: string;
  minutes: number;
  comment: string;
}

export interface OurHours {
  familyId: string | null;
  familyMonthlyMinutes: number;
  monthsInSemester: number;
  sollMinutes: number;
  istMinutes: number;
  allGroups: boolean;
  children: OurHoursChild[];
  months: OurHoursMonthRow[];
  entries: OurHoursEntry[];
}
