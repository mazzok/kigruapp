import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { KostenDiscount } from '../models/kosten-discount.model';

@Injectable({ providedIn: 'root' })
export class KostenDiscountService {
  private readonly base = '/api/v1/kosten-discount';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<KostenDiscount> {
    return this.http.get<KostenDiscount>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: KostenDiscount): Observable<KostenDiscount> {
    return this.http.put<KostenDiscount>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
