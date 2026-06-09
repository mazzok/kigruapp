export type EntityType = 'CHILD' | 'PARENT' | 'FAMILY';

export interface FieldDefinition {
  id?: string;
  entity: EntityType;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  createdAt?: string;
  outdatedAt?: string | null;
}
