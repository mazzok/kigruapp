import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import {
  LandingContext,
  LandingPage,
  LandingPageImageUpload,
  LandingPlaceholder,
} from '../models/landing-page.model';

@Injectable({ providedIn: 'root' })
export class LandingPageService {
  constructor(private api: ApiService) {}

  get(): Observable<LandingPage> {
    return this.api.get<LandingPage>('/landing-page');
  }

  save(bodyHtml: string): Observable<LandingPage> {
    return this.api.put<LandingPage>('/landing-page', { bodyHtml });
  }

  /** Lädt ein eingefügtes Bild hoch; die Antwort-URL wird statt einer Base64-data-URI in den Editor eingefügt. */
  uploadImage(file: File): Observable<LandingPageImageUpload> {
    return this.api.postBinary<LandingPageImageUpload>('/landing-page/images', file, file.type);
  }

  placeholders(): Observable<LandingPlaceholder[]> {
    return this.api.get<LandingPlaceholder[]>('/landing-page/placeholders');
  }

  context(): Observable<LandingContext> {
    return this.api.get<LandingContext>('/landing-page/context');
  }
}
