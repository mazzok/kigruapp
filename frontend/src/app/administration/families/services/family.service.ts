import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Family } from '../../../shared/models/family.model';
import { Person } from '../../../shared/models/person.model';
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

  getPersons(familyId: string): Observable<Person[]> {
    return this.api.get<Person[]>(`/families/${familyId}/persons`);
  }
}
