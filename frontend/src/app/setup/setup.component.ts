import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../core/services/auth.service';

@Component({
  selector: 'app-setup',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule,
    MatFormFieldModule, MatInputModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './setup.component.html',
  styleUrl: './setup.component.scss',
})
export class SetupComponent implements OnInit {
  familyName = '';
  loading = false;
  error = '';
  setupComplete = false;

  constructor(
    public auth: AuthService,
    private http: HttpClient,
    private router: Router,
  ) {}

  ngOnInit(): void {
    if (this.auth.isAuthenticated) {
      this.checkAndAutoSetup();
    }
  }

  private checkAndAutoSetup(): void {
    this.http.get<{ required: boolean }>('/api/v1/setup/status').subscribe(status => {
      if (!status.required) {
        this.router.navigate(['/cooking']);
      }
    });
  }

  loginWithKeycloak(): void {
    this.auth.login();
  }

  submitSetup(): void {
    if (!this.familyName.trim()) {
      this.error = 'Bitte gib einen Familiennamen ein.';
      return;
    }
    this.loading = true;
    this.error = '';

    const oauthSvc = (this.auth as unknown as { oauthService: { getIdentityClaims: () => Record<string, string> | null } }).oauthService;
    const claims = oauthSvc?.getIdentityClaims?.() ?? null;
    const body = {
      familyName: this.familyName.trim(),
      keycloakUserId: claims?.['sub'] ?? '',
      email: this.auth.userEmail,
      firstName: claims?.['given_name'] ?? '',
      lastName: claims?.['family_name'] ?? '',
    };

    this.http.post('/api/v1/setup', body).subscribe({
      next: () => {
        this.setupComplete = true;
        this.router.navigate(['/cooking']);
      },
      error: err => {
        this.loading = false;
        this.error = (err.error as { error?: string })?.error ?? 'Einrichtung fehlgeschlagen. Bitte neu laden.';
      },
    });
  }
}
