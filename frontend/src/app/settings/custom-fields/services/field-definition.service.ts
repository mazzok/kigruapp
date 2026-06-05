import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { FieldDefinition } from '../../../shared/models/field-definition.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FieldDefinitionService {
  constructor(private api: ApiService) {}

  list(): Observable<FieldDefinition[]> {
    return this.api.get<FieldDefinition[]>('/field-definitions');
  }

  create(fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.post<FieldDefinition>('/field-definitions', fieldDef);
  }

  update(id: string, fieldDef: FieldDefinition): Observable<FieldDefinition> {
    return this.api.put<FieldDefinition>(`/field-definitions/${id}`, fieldDef);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/field-definitions/${id}`);
  }
}
