import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { FieldInstanceDTO } from '../models/field-instance.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldInstanceService {
  constructor(private api: ApiService) {}

  get(id: string): Observable<FieldInstanceDTO> {
    return this.api.get<FieldInstanceDTO>(`/field-instances/${id}`);
  }

  batchSave(instances: { definitionId: string; value: unknown }[]): Observable<unknown> {
    return this.api.put('/field-instances/batch', instances);
  }
}
