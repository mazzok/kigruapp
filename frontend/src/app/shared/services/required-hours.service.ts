import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RequiredHours } from '../models/required-hours.model';

@Injectable({ providedIn: 'root' })
export class RequiredHoursService {
  private readonly base = '/api/v1/required-hours';

  constructor(private http: HttpClient) {}

  get(semesterId: string): Observable<RequiredHours> {
    return this.http.get<RequiredHours>(`${this.base}?semesterId=${semesterId}`);
  }

  save(semesterId: string, dto: RequiredHours): Observable<RequiredHours> {
    return this.http.put<RequiredHours>(`${this.base}?semesterId=${semesterId}`, dto);
  }
}
