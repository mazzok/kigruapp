import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Parent } from '../../../shared/models/parent.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ParentService {
  constructor(private api: ApiService) {}

  create(parent: Parent): Observable<Parent> {
    return this.api.post<Parent>('/parents', parent);
  }

  update(id: string, parent: Parent): Observable<Parent> {
    return this.api.put<Parent>(`/parents/${id}`, parent);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/parents/${id}`);
  }
}
