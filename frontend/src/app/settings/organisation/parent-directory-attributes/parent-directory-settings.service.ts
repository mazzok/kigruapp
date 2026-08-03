import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../../core/services/api.service';

export type ParentDirectoryScope = 'CHILD' | 'PARENT' | 'FAMILY';

export interface ParentDirectoryAttribute {
  key: string;
  label: string;
  scope: ParentDirectoryScope;
  selected: boolean;
  locked: boolean;
}

export interface ParentDirectoryAttributeCatalog {
  attributes: ParentDirectoryAttribute[];
}

@Injectable({ providedIn: 'root' })
export class ParentDirectorySettingsService {
  constructor(private api: ApiService) {}

  load(): Observable<ParentDirectoryAttributeCatalog> {
    return this.api.get<ParentDirectoryAttributeCatalog>('/parent-directory/attributes');
  }

  save(visibleAttributes: string[]): Observable<void> {
    return this.api.put<void>('/parent-directory/attributes', { visibleAttributes });
  }
}
