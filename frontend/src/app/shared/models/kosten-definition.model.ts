import { Currency } from './currency.model';

export interface KostenDefinition {
  id: string;
  label: string;
  active: boolean;
  currency: Currency;
}

export interface CreateKostenDefinitionRequest {
  label: string;
  currencyId: string;
}
