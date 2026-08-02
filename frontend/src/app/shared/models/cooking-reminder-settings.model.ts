export interface CookingReminderSettings {
  senderAccountId: string | null;
  templateId: string | null;
  subject: string | null;
  /** Versandzeit im Format HH:mm, Zeitzone Europe/Vienna. */
  sendTime: string;
  /** Vom Backend abgeleitet: Konto und Vorlage gesetzt und nutzbar. */
  active: boolean;
}
