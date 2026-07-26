export interface MailTemplate {
  id: string;
  name: string;
  bodyHtml: string;
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
}
