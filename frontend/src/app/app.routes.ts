import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'administration',
    canActivate: [authGuard],
    children: [
      {
        path: 'families',
        loadComponent: () =>
          import('./administration/families/family-list/family-list.component').then(
            (m) => m.FamilyListComponent
          ),
      },
    ],
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    children: [
      {
        path: 'custom-fields',
        loadComponent: () =>
          import('./settings/custom-fields/custom-fields.component').then(
            (m) => m.CustomFieldsComponent
          ),
      },
      {
        path: 'permissions',
        loadComponent: () =>
          import('./settings/permissions/permissions.component').then(
            (m) => m.PermissionsComponent
          ),
      },
    ],
  },
  { path: '', redirectTo: 'administration/families', pathMatch: 'full' },
];
