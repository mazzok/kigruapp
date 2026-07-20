import { Currency } from './currency.model';

export interface KostenValue {
  definitionId: string;
  label: string;
  currency: Currency;
  amount: number | null;
}

export interface UpsertKostenValueRequest {
  semesterId: string;
  groupId: string;
  definitionId: string;
  amount: number | null;
}
