import { MailTemplateKind } from './mail-template.model';

export type RecipientKind = 'GROUP' | 'TEAM' | 'ROLE';

export interface RecipientSelection {
  kind: RecipientKind;
  fieldInstanceId: string;
}

export interface MailJob {
  id: string;
  name: string;
  templateId: string;
  subject: string;
  senderAccountId: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  kind: MailTemplateKind;
  sendTime: string | null;
  lastRunAt: string | null;
  lastRunStatus: string | null;
  lastRunError: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SaveMailJobRequest {
  name: string;
  templateId: string;
  subject: string;
  senderAccountId: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
}
