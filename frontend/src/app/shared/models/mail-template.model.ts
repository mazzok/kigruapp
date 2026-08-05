export type MailTemplateKind = 'GENERAL' | 'COOKING_REMINDER' | 'COOKING_OVERVIEW';

export interface MailTemplate {
  id: string;
  name: string;
  bodyHtml: string;
  kind: MailTemplateKind;
  createdAt: string;
  updatedAt: string;
}

export interface SaveMailTemplateRequest {
  name: string;
  bodyHtml: string;
}

export interface PlaceholderTile {
  token: string;
  fieldName: string;
  label: Record<string, string>;
  group: string;
  groupLabel: string;
}
