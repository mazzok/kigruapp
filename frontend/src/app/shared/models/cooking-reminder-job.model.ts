export interface CookingReminderJob {
  id: string;
  name: string;
  senderAccountId: string;
  subject: string;
  sendTime: string;
  active: boolean;
  templateId: string;
  templateName: string;
  templateBodyHtml: string;
}

export interface SaveCookingReminderJobRequest {
  name: string;
  senderAccountId: string;
  subject: string;
  sendTime: string;
  active: boolean;
  templateName: string;
  templateBodyHtml: string;
}
