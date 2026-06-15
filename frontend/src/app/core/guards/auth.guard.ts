import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const http = inject(HttpClient);
  const router = inject(Router);

  try {
    const status = await firstValueFrom(
      http.get<{ required: boolean }>('/api/v1/setup/status')
    );
    if (status.required) {
      router.navigate(['/setup']);
      return false;
    }
  } catch {
    // Backend unreachable — proceed to auth check
  }

  if (!auth.isAuthenticated) {
    auth.login();
    return false;
  }
  return true;
};
