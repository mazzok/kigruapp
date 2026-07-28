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
