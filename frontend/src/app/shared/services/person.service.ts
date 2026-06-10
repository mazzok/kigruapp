import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { Person, CreatePersonRequest, PersonDTO } from '../models/person.model';
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

  create(request: CreatePersonRequest): Observable<Person> {
    return this.api.post<Person>('/persons', request);
  }

  update(id: string, person: Person): Observable<Person> {
    return this.api.put<Person>(`/persons/${id}`, person);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/persons/${id}`);
  }
}
