import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { catchError, forkJoin, of } from 'rxjs';
import { LandingPageService } from '../shared/services/landing-page.service';
import { renderWithContext } from '../shared/landing-token.util';
import { LandingContext, LandingPage } from '../shared/models/landing-page.model';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [CommonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent implements OnInit {
  renderedHtml: SafeHtml;
  isEmpty = false;
  loading = true;

  constructor(
    private landingPageService: LandingPageService,
    private sanitizer: DomSanitizer,
  ) {
    this.renderedHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    // Beide Fehler werden aufgefangen: die Startseite ist der Einstiegspunkt in
    // die App und darf an einem fehlgeschlagenen Request nicht scheitern.
    forkJoin({
      page: this.landingPageService.get().pipe(
        catchError(() => of<LandingPage>({ bodyHtml: '', updatedAt: null })),
      ),
      context: this.landingPageService.context().pipe(
        catchError(() => of<LandingContext>({})),
      ),
    }).subscribe(({ page, context }) => {
      const body = page.bodyHtml ?? '';
      this.isEmpty = body.trim().length === 0;
      // Das Backend hat beim Speichern bereits sanitisiert.
      this.renderedHtml = this.sanitizer.bypassSecurityTrustHtml(
        renderWithContext(body, context),
      );
      this.loading = false;
    });
  }
}
