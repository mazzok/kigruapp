import { RecipientSelection } from './mail-job.model';

export interface CookingOverviewJob {
  id: string;
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateId: string;
  templateName: string;
  templateBodyHtml: string;
}

export interface SaveCookingOverviewJobRequest {
  name: string;
  senderAccountId: string;
  subject: string;
  cron: string;
  allParents: boolean;
  recipientSelections: RecipientSelection[];
  active: boolean;
  templateName: string;
  templateBodyHtml: string;
}
