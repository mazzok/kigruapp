import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { KostenValue, UpsertKostenValueRequest } from '../models/kosten-value.model';

@Injectable({ providedIn: 'root' })
export class KostenValueService {
  constructor(private api: ApiService) {}

  getForSemesterAndGroup(semesterId: string, groupId: string): Observable<KostenValue[]> {
    return this.api.get<KostenValue[]>(`/kosten-values?semesterId=${semesterId}&groupId=${groupId}`);
  }

  upsert(request: UpsertKostenValueRequest): Observable<void> {
    return this.api.put<void>('/kosten-values', request);
  }
}
