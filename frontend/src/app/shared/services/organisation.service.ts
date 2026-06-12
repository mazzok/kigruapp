import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { OrganisationDTO } from '../models/organisation.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class OrganisationService {
  constructor(private api: ApiService) {}

  list(): Observable<OrganisationDTO[]> {
    return this.api.get<OrganisationDTO[]>('/organisation');
  }

  getByTag(tag: string): Observable<OrganisationDTO> {
    return this.api.get<OrganisationDTO>(`/organisation/${tag}`);
  }

  update(id: string, org: { definitionIds: string[]; entries?: { name: string; definitionIds: string[] }[] }): Observable<OrganisationDTO> {
    return this.api.put<OrganisationDTO>(`/organisation/id/${id}`, org);
  }
}
