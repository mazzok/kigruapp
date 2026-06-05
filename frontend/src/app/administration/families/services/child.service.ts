import { Injectable } from '@angular/core';
import { ApiService } from '../../../core/services/api.service';
import { Child } from '../../../shared/models/child.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ChildService {
  constructor(private api: ApiService) {}

  create(child: Child): Observable<Child> {
    return this.api.post<Child>('/children', child);
  }

  update(id: string, child: Child): Observable<Child> {
    return this.api.put<Child>(`/children/${id}`, child);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/children/${id}`);
  }
}
