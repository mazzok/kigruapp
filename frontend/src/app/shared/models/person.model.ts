import { FieldInstanceDTO } from './field-instance.model';

export interface FieldRef {
  definitionId: string;
  fieldInstanceId: string;
}

export interface Person {
  id?: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldRef[];
  roles: FieldRef[];
  schedules: FieldRef[];
  duties: FieldRef[];
  finance: FieldRef[];
  customProperties: FieldRef[];
  organisationalUnit: FieldRef[];
  assignedDuty: FieldRef[];
  createdAt?: string;
  updatedAt?: string;
}

export interface CreatePersonRequest {
  familyId: string;
  basicProperties: SectionInput[];
  roles?: SectionInput[];
  schedules?: SectionInput[];
  duties?: SectionInput[];
  finance?: SectionInput[];
  customProperties?: SectionInput[];
  organisationalUnit?: SectionInput[];
}

export interface SectionInput {
  definitionId: string;
  value: unknown;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId?: string;
  basicProperties: FieldInstanceDTO[];
  roles: FieldInstanceDTO[];
  schedules: FieldInstanceDTO[];
  duties: FieldInstanceDTO[];
  finance: FieldInstanceDTO[];
  customProperties: FieldInstanceDTO[];
  organisationalUnit: FieldInstanceDTO[];
  assignedDuty: FieldInstanceDTO[];
  createdAt?: string;
  updatedAt?: string;
}

export interface ChildDTO {
  id: string;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  groupDefinitionId: string | null;
  groupInstanceId: string | null;
}
