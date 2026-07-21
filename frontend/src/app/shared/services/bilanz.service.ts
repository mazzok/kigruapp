import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { BilanzMatrix, BilanzCell, UpsertOverrideRequest } from '../models/bilanz.model';

@Injectable({ providedIn: 'root' })
export class BilanzService {
  constructor(private api: ApiService) {}

  getMatrix(year: number): Observable<BilanzMatrix> {
    return this.api.get<BilanzMatrix>(`/bilanzen?year=${year}`);
  }

  getCell(personId: string, year: number, month: number): Observable<BilanzCell> {
    return this.api.get<BilanzCell>(`/bilanzen/cell?personId=${personId}&year=${year}&month=${month}`);
  }

  upsertOverride(req: UpsertOverrideRequest): Observable<void> {
    return this.api.put<void>('/bilanzen/overrides', req);
  }
}
