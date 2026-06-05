import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Family } from '../../../shared/models/family.model';
import { Child } from '../../../shared/models/child.model';
import { Parent } from '../../../shared/models/parent.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class FamilyService {
  constructor(private api: ApiService) {}

  list(): Observable<Family[]> {
    return this.api.get<Family[]>('/families');
  }

  get(id: string): Observable<Family> {
    return this.api.get<Family>(`/families/${id}`);
  }

  create(family: Family): Observable<Family> {
    return this.api.post<Family>('/families', family);
  }

  update(id: string, family: Family): Observable<Family> {
    return this.api.put<Family>(`/families/${id}`, family);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/families/${id}`);
  }

  getChildren(familyId: string): Observable<Child[]> {
    return this.api.get<Child[]>(`/families/${familyId}/children`);
  }

  getParents(familyId: string): Observable<Parent[]> {
    return this.api.get<Parent[]>(`/families/${familyId}/parents`);
  }
}
