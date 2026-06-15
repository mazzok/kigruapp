import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';

export interface FieldInstanceDTO {
  id: string;
  definitionId: string;
  fieldName: string;
  label: Record<string, string>;
  description: string;
  jsonSchema: Record<string, unknown>;
  required: boolean;
  keycloakMapping: string;
  value: unknown;
  definitionOutdated: boolean;
}

export interface PersonDTO {
  id: string;
  familyId: string;
  keycloakUserId: string;
  basicProperties: FieldInstanceDTO[];
  roles: FieldInstanceDTO[];
  schedules: FieldInstanceDTO[];
  duties: FieldInstanceDTO[];
  finance: FieldInstanceDTO[];
  customProperties: FieldInstanceDTO[];
  createdAt: string;
  updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class CurrentUserService {
  private personSubject = new BehaviorSubject<PersonDTO | null>(null);
  currentPerson$ = this.personSubject.asObservable();

  constructor(private http: HttpClient) {}

  get currentPerson(): PersonDTO | null {
    return this.personSubject.value;
  }

  get currentFamilyId(): string {
    return this.currentPerson?.familyId ?? '';
  }

  get isAdmin(): boolean {
    return this.currentPerson?.roles?.some(r => r.value === 'ADMIN') ?? false;
  }

  loadCurrentUser(): Observable<PersonDTO> {
    return this.http.get<PersonDTO>('/api/v1/persons/me').pipe(
      tap(person => this.personSubject.next(person))
    );
  }

  clear(): void {
    this.personSubject.next(null);
  }
}
