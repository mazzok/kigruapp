import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Person, CreatePersonRequest, PersonDTO, ChildDTO } from '../models/person.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class PersonService {
  constructor(private api: ApiService) {}

  list(familyId?: string): Observable<Person[]> {
    const params = familyId ? `?familyId=${familyId}` : '';
    return this.api.get<Person[]>(`/persons${params}`);
  }

  get(id: string): Observable<Person> {
    return this.api.get<Person>(`/persons/${id}`);
  }

  getFull(id: string): Observable<PersonDTO> {
    return this.api.get<PersonDTO>(`/persons/${id}/full`);
  }

  getChildren(): Observable<ChildDTO[]> {
    return this.api.get<ChildDTO[]>('/persons/children');
  }

  create(request: CreatePersonRequest): Observable<Person> {
    return this.api.post<Person>('/persons', request);
  }

  assignGroup(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
    return this.api.patch<void>(`/persons/${personId}/group`, { definitionId, fieldInstanceId });
  }

  assignTeam(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
    return this.api.patch<void>(`/persons/${personId}/assigned-duty`, { definitionId, fieldInstanceId });
  }

  assignRole(personId: string, definitionId: string, fieldInstanceId: string): Observable<void> {
    return this.api.patch<void>(`/persons/${personId}/assigned-role`, { definitionId, fieldInstanceId });
  }

  update(id: string, request: CreatePersonRequest): Observable<Person> {
    return this.api.put<Person>(`/persons/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/persons/${id}`);
  }
}
