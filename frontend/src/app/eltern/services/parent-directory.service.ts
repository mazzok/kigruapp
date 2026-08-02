import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { ParentDirectory } from '../../shared/models/parent-directory.model';

@Injectable({ providedIn: 'root' })
export class ParentDirectoryService {
  constructor(private api: ApiService) {}

  load(): Observable<ParentDirectory> {
    return this.api.get<ParentDirectory>('/parent-directory');
  }
}
