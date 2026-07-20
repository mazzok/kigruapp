import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { KostenDefinition, CreateKostenDefinitionRequest } from '../models/kosten-definition.model';

@Injectable({ providedIn: 'root' })
export class KostenDefinitionService {
  constructor(private api: ApiService) {}

  getAll(): Observable<KostenDefinition[]> {
    return this.api.get<KostenDefinition[]>('/kosten-definitions');
  }

  create(request: CreateKostenDefinitionRequest): Observable<KostenDefinition> {
    return this.api.post<KostenDefinition>('/kosten-definitions', request);
  }

  setActive(id: string, active: boolean): Observable<KostenDefinition> {
    return this.api.patch<KostenDefinition>(`/kosten-definitions/${id}/active`, { active });
  }
}
