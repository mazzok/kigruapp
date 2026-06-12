export interface FieldDefinition {
  id?: string;
  fieldName: string;
  label: Record<string, string>;
  description?: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping?: string | null;
  properties?: Record<string, unknown>;
  createdAt?: string;
  outdatedAt?: string | null;
}
