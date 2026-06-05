export interface Child {
  id?: string;
  familyId: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string;
  gender: string;
  entryDate?: string;
  exitDate?: string;
  notes?: string;
  customFields?: Record<string, unknown>;
}
