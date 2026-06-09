import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { EntityType, FieldDefinition } from '../../../shared/models/field-definition.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldDefinitionService {
  constructor(private api: ApiService) {}

  list(): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>('/field-definitions');
  }

  listByEntity(entity: EntityType): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>(`/field-definitions?entity=${entity}`);
  }

  listActive(entity: EntityType): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>(`/field-definitions?entity=${entity}&active=true`);
  }

  create(fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.post<FieldDefinition>('/field-definitions', fieldDef);
  }

  update(id: string, fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.put<FieldDefinition>(`/field-definitions/${id}`, fieldDef);
  }

  outdate(id: string): Observable<FieldDefinition> {
    return this.api.patch<FieldDefinition>(`/field-definitions/${id}/outdate`);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/field-definitions/${id}`);
  }
}
