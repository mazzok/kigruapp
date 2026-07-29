import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AliquotConfig } from '../models/aliquot-config.model';

@Injectable({ providedIn: 'root' })
export class AliquotConfigService {
  private readonly base = '/api/v1/aliquot-config';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<AliquotConfig> {
    return this.http.get<AliquotConfig>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: AliquotConfig): Observable<AliquotConfig> {
    return this.http.put<AliquotConfig>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
