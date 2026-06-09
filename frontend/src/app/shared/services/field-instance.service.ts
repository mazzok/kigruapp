import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { EntityType } from '../models/field-definition.model';
import { FieldInstanceDTO } from '../models/field-instance.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldInstanceService {
  constructor(private api: ApiService) {}

  listForEntity(entityType: EntityType, entityId: string): Observable<FieldInstanceDTO[]> {
    return this.api.get<FieldInstanceDTO[]>(
      `/field-instances?entityType=${entityType}&entityId=${entityId}`
    );
  }

  batchSave(
    entityType: EntityType,
    entityId: string,
    instances: { definitionId: string; value: unknown }[]
  ): Observable<unknown> {
    return this.api.put(
      `/field-instances/batch?entityType=${entityType}&entityId=${entityId}`,
      instances
    );
  }
}
