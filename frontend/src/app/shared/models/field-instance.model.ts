import { EntityType } from './field-definition.model';

export interface FieldInstance {
  id?: string;
  definitionId: string;
  entityType: EntityType;
  entityId: string;
  value: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface FieldInstanceDTO {
  id?: string;
  definitionId: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  value: unknown;
  definitionOutdated: boolean;
}
