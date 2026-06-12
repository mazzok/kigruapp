import { Injectable } from '@angular/core';
import { ApiService } from '../../core/services/api.service';
import { CookingDutyDTO } from '../../shared/models/organisation.model';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class CookingDutyService {
  constructor(private api: ApiService) {}

  getByMonth(month: string, groups?: string[]): Observable<CookingDutyDTO[]> {
    let params = `?month=${month}`;
    if (groups && groups.length > 0) {
      params += `&groups=${groups.join(',')}`;
    }
    return this.api.get<CookingDutyDTO[]>(`/cooking-duties${params}`);
  }
}
