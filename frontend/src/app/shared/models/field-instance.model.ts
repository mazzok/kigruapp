export interface FieldInstance {
  id?: string;
  definitionId: string;
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
  keycloakMapping?: string | null;
  value: unknown;
  definitionOutdated: boolean;
}
