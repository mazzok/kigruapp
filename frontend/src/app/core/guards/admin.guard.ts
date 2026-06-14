import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { CurrentUserService } from '../services/current-user.service';

export const adminGuard: CanActivateFn = () => {
  const currentUser = inject(CurrentUserService);
  const router = inject(Router);

  if (currentUser.isAdmin) return true;

  router.navigate(['/cooking']);
  return false;
};
