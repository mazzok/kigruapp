import { FieldDefinition } from './field-definition.model';

export interface DutyEntryDTO {
  name: string;
  definitions: FieldDefinition[];
}

export interface OrganisationDTO {
  id: string;
  tag: string;
  definitions: FieldDefinition[];
  entries: DutyEntryDTO[];
}

export interface CookingDutyDTO {
  id: string;
  personId: string;
  familyId: string;
  personName: string;
  date: string;
  groups: string[];
  description: string;
  foodProperties: Record<string, boolean>;
}
