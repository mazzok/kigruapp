export interface MailBlockDefinition {
  type: string;
  label: string;
  icon: string;
}

export interface CookingDutyBlockConfig {
  type: 'cookingDuty';
  groupId: string;
  periodUnit: 'week' | 'month';
  periodAmount: number;
}

export type MailBlockConfig = CookingDutyBlockConfig;

export const MAIL_BLOCK_DEFINITIONS: MailBlockDefinition[] = [
  { type: 'cookingDuty', label: 'Kochdienst-Tabelle', icon: 'restaurant' },
];

export const DEFAULT_BLOCK_CONFIG: Record<string, MailBlockConfig> = {
  cookingDuty: { type: 'cookingDuty', groupId: '', periodUnit: 'week', periodAmount: 2 },
};

/** Dropdown range for the "Anzahl" select in the block config dialog. */
export const PERIOD_AMOUNT_OPTIONS: number[] = Array.from({ length: 12 }, (_, i) => i + 1);
