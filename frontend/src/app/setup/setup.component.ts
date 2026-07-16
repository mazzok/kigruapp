import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatStepperModule } from '@angular/material/stepper';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../core/services/auth.service';
import { ParentsStepComponent } from '../administration/families/family-wizard/steps/parents-step.component';

@Component({
  selector: 'app-setup',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule,
    MatFormFieldModule, MatInputModule,
    MatProgressSpinnerModule, MatStepperModule, MatIconModule,
    ParentsStepComponent,
  ],
  templateUrl: './setup.component.html',
  styleUrl: './setup.component.scss',
})
export class SetupComponent implements OnInit {
  @ViewChild(ParentsStepComponent) parentsStep?: ParentsStepComponent;

  familyName = '';
  loading = false;
  error = '';
  setupComplete = false;

  keycloakPrefill: { firstName: string; lastName: string; email: string } | null = null;

  constructor(
    public auth: AuthService,
    private http: HttpClient,
    private router: Router,
  ) {}

  ngOnInit(): void {
    if (this.auth.isAuthenticated) {
      this.checkAndAutoSetup();
      this.loadKeycloakData();
    }
  }

  private checkAndAutoSetup(): void {
    this.http.get<{ required: boolean }>('/api/v1/setup/status').subscribe(status => {
      if (!status.required) {
        this.router.navigate(['/cooking']);
      }
    });
  }

  private loadKeycloakData(): void {
    const oauthSvc = (this.auth as unknown as {
      oauthService: { getIdentityClaims: () => Record<string, string> | null }
    }).oauthService;
    const claims = oauthSvc?.getIdentityClaims?.() ?? null;
    if (claims) {
      this.keycloakPrefill = {
        firstName: claims['given_name'] ?? '',
        lastName: claims['family_name'] ?? '',
        email: this.auth.userEmail ?? claims['email'] ?? '',
      };
      if (!this.familyName && this.keycloakPrefill.lastName) {
        this.familyName = this.keycloakPrefill.lastName;
      }
    }
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

    const oauthSvc = (this.auth as unknown as {
      oauthService: { getIdentityClaims: () => Record<string, string> | null }
    }).oauthService;
    const claims = oauthSvc?.getIdentityClaims?.() ?? null;

    const parentProperties = this.parentsStep?.getParentsBasicProperties?.()?.[0];
    if (!parentProperties) {
      this.loading = false;
      this.error = 'Bitte füllen Sie die Elterndaten aus.';
      return;
    }

    const body = {
      familyName: this.familyName.trim(),
      keycloakUserId: claims?.['sub'] ?? '',
      email: this.keycloakPrefill?.email ?? this.auth.userEmail ?? '',
      firstName: this.keycloakPrefill?.firstName ?? '',
      lastName: this.keycloakPrefill?.lastName ?? '',
      parentProperties,
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
