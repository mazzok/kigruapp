import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { Currency, CreateCurrencyRequest } from '../models/currency.model';

@Injectable({ providedIn: 'root' })
export class CurrencyService {
  constructor(private api: ApiService) {}

  getAll(): Observable<Currency[]> {
    return this.api.get<Currency[]>('/currencies');
  }

  create(request: CreateCurrencyRequest): Observable<Currency> {
    return this.api.post<Currency>('/currencies', request);
  }
}
