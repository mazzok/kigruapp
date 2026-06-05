export type EntityType = 'CHILD' | 'PARENT' | 'FAMILY';
export type FieldType = 'TEXT' | 'DATE' | 'SELECT' | 'BOOLEAN';

export interface FieldDefinition {
  id?: string;
  entity: EntityType;
  fieldName: string;
  label: Record<string, string>;
  type: FieldType;
  options?: string[];
  required: boolean;
}
