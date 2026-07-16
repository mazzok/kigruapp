import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { Semester, CreateSemesterRequest } from '../models/semester.model';

@Injectable({ providedIn: 'root' })
export class SemesterService {
  constructor(private api: ApiService) {}

  getAll(): Observable<Semester[]> {
    return this.api.get<Semester[]>('/semesters');
  }

  create(request: CreateSemesterRequest): Observable<Semester> {
    return this.api.post<Semester>('/semesters', request);
  }
}
