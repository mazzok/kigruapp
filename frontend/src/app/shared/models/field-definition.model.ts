export interface FieldDefinition {
  id?: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping?: string | null;
  createdAt?: string;
  outdatedAt?: string | null;
}
