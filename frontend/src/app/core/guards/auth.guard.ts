import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  try {
    return await auth.login();
  } catch (e) {
    console.warn('Auth discovery failed, allowing unauthenticated access:', e);
    return true;
  }
};
