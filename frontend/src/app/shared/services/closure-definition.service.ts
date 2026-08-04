import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ClosureDefinition, ClosureDefinitionRequest } from '../models/closure.model';

@Injectable({ providedIn: 'root' })
export class ClosureDefinitionService {
  constructor(private api: ApiService) {}

  getAll(includeInactive = false): Observable<ClosureDefinition[]> {
    const path = includeInactive
      ? '/closure-definitions?includeInactive=true'
      : '/closure-definitions';
    return this.api.get<ClosureDefinition[]>(path);
  }

  create(request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.post<ClosureDefinition>('/closure-definitions', request);
  }

  /** Nur erlaubt, solange keine Zeitraeume verknuepft sind — sonst antwortet das Backend mit 409. */
  update(id: string, request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.put<ClosureDefinition>(`/closure-definitions/${id}`, request);
  }

  /** Legt eine Kopie mit den neuen Werten an und deaktiviert das Original. */
  revise(id: string, request: ClosureDefinitionRequest): Observable<ClosureDefinition> {
    return this.api.post<ClosureDefinition>(`/closure-definitions/${id}/revise`, request);
  }

  /** Loescht nicht, sondern setzt active auf false. */
  deactivate(id: string): Observable<void> {
    return this.api.delete(`/closure-definitions/${id}`);
  }
}
