export type RecipientMode = 'GROUPS' | 'ALL_PARENTS';

export interface MailJob {
  id: string;
  name: string;
  templateId: string;
  subject: string;
  senderAccountId: string;
  cron: string;
  recipientMode: RecipientMode;
  recipientGroupDefinitionIds: string[];
  active: boolean;
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
  recipientMode: RecipientMode;
  recipientGroupDefinitionIds: string[];
}
