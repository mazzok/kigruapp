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

  getFull(id: string, semesterId?: string): Observable<PersonDTO> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.get<PersonDTO>(`/persons/${id}/full${params}`);
  }

  getChildren(semesterId?: string): Observable<ChildDTO[]> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.get<ChildDTO[]>(`/persons/children${params}`);
  }

  create(request: CreatePersonRequest): Observable<Person> {
    return this.api.post<Person>('/persons', request);
  }

  assignGroup(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string): Observable<void> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.patch<void>(`/persons/${personId}/group${params}`, { definitionId, fieldInstanceId });
  }

  setEnrollmentDates(personId: string, entryDate: string | null, exitDate: string | null, semesterId?: string): Observable<void> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.patch<void>(`/persons/${personId}/enrollment-dates${params}`, { entryDate, exitDate });
  }

  assignTeam(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string): Observable<void> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.patch<void>(`/persons/${personId}/assigned-duty${params}`, { definitionId, fieldInstanceId });
  }

  assignRole(personId: string, definitionId: string, fieldInstanceId: string, semesterId?: string): Observable<void> {
    const params = semesterId ? `?semesterId=${semesterId}` : '';
    return this.api.patch<void>(`/persons/${personId}/assigned-role${params}`, { definitionId, fieldInstanceId });
  }

  update(id: string, request: CreatePersonRequest): Observable<Person> {
    return this.api.put<Person>(`/persons/${id}`, request);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/persons/${id}`);
  }
}
