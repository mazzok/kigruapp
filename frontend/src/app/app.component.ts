import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from './core/services/auth.service';
import { CurrentUserService } from './core/services/current-user.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, RouterModule,
    MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  constructor(
    public auth: AuthService,
    public currentUser: CurrentUserService,
  ) {}

  ngOnInit(): void {
    // Always attempt to load — works in dev mode (no OIDC) and after production login
    this.currentUser.loadCurrentUser().subscribe({ error: () => {} });
    // After Keycloak redirect login the token arrives asynchronously — reload user then too
    this.auth.tokenReceived$.subscribe(() => {
      this.currentUser.loadCurrentUser().subscribe();
    });
  }
}
