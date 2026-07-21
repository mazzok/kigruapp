import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CurrentUserService } from '../services/current-user.service';

export const adminGuard: CanActivateFn = async () => {
  const currentUser = inject(CurrentUserService);
  const router = inject(Router);

  if (!currentUser.currentPerson) {
    try {
      await firstValueFrom(currentUser.loadCurrentUser());
    } catch {
      // Backend unreachable or unauthenticated — isAdmin stays false, handled below.
    }
  }

  if (currentUser.isAdmin) return true;

  router.navigate(['/cooking']);
  return false;
};
