export interface Currency {
  id: string;
  code: string;
  symbol: string;
}

export interface CreateCurrencyRequest {
  code: string;
  symbol: string;
}
