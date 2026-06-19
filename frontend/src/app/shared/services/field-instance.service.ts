import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { FieldInstance, FieldInstanceDTO } from '../models/field-instance.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldInstanceService {
  constructor(private api: ApiService) {}

  get(id: string): Observable<FieldInstanceDTO> {
    return this.api.get<FieldInstanceDTO>(`/field-instances/${id}`);
  }

  create(definitionId: string, value: unknown): Observable<{ id: string }> {
    return this.api.post<{ id: string }>('/field-instances', { definitionId, value });
  }

  update(id: string, fieldInstance: FieldInstance): Observable<FieldInstance> {
    return this.api.put<FieldInstance>(`/field-instances/${id}`, fieldInstance);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/field-instances/${id}`);
  }

  batchSave(instances: { definitionId: string; value: unknown }[]): Observable<unknown> {
    return this.api.put('/field-instances/batch', instances);
  }
}
