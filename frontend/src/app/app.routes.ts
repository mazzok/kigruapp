import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  {
    path: 'setup',
    loadComponent: () =>
      import('./setup/setup.component').then(m => m.SetupComponent),
  },
  {
    path: 'cooking',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./cooking/cooking.component').then(m => m.CookingComponent),
  },
  {
    path: 'administration',
    canActivate: [authGuard, adminGuard],
    children: [
      {
        path: 'families',
        loadComponent: () =>
          import('./administration/families/family-list/family-list.component').then(
            m => m.FamilyListComponent
          ),
      },
      {
        path: 'platzzuweisung',
        loadComponent: () =>
          import('./administration/platzzuweisung/platzzuweisung.component').then(
            m => m.PlatzzuweisungComponent
          ),
      },
      {
        path: 'kosten-pro-semester',
        loadComponent: () =>
          import('./administration/kosten-pro-semester/kosten-pro-semester.component').then(
            m => m.KostenProSemesterComponent
          ),
      },
      {
        path: 'bilanzen',
        loadComponent: () =>
          import('./administration/bilanzen/bilanzen.component').then(
            m => m.BilanzenComponent
          ),
      },
      {
        path: 'elterneinteilung',
        loadComponent: () =>
          import('./administration/elterneinteilung/elterneinteilung.component').then(
            m => m.ElterneinteilungComponent
          ),
      },
      {
        path: 'board',
        loadComponent: () =>
          import('./administration/board/board.component').then(
            m => m.BoardComponent
          ),
      },
    ],
  },
  {
    path: 'settings',
    canActivate: [authGuard, adminGuard],
    children: [
      {
        path: 'organisation',
        loadComponent: () =>
          import('./settings/organisation/organisation.component').then(
            m => m.OrganisationComponent
          ),
      },
      {
        path: 'custom-fields',
        loadComponent: () =>
          import('./settings/custom-fields/custom-fields.component').then(
            m => m.CustomFieldsComponent
          ),
      },
      {
        path: 'permissions',
        loadComponent: () =>
          import('./settings/permissions/permissions.component').then(
            m => m.PermissionsComponent
          ),
      },
    ],
  },
  { path: '', redirectTo: 'cooking', pathMatch: 'full' },
];
