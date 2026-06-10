import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { PersonService } from '../../shared/services/person.service';
import { ApiService } from '../../core/services/api.service';
import { Person } from '../../shared/models/person.model';

const AVAILABLE_PERMISSIONS = [
  'families.read',
  'families.write',
  'settings.admin',
  'permissions.manage',
];

interface PermissionRow {
  personId: string;
  name: string;
  permissions: string[];
}

@Component({
  selector: 'app-permissions',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatCheckboxModule, MatButtonModule],
  templateUrl: './permissions.component.html',
  styleUrl: './permissions.component.scss',
})
export class PermissionsComponent implements OnInit {
  displayedColumns = ['name', ...AVAILABLE_PERMISSIONS, 'actions'];
  availablePermissions = AVAILABLE_PERMISSIONS;
  dataSource = new MatTableDataSource<PermissionRow>();

  constructor(private api: ApiService, private personService: PersonService) {}

  ngOnInit(): void {
    this.loadPersons();
  }

  loadPersons(): void {
    // TODO: Permissions need redesign for person architecture
    // For now, this is a placeholder that loads an empty list
    this.dataSource.data = [];
  }

  hasPermission(row: PermissionRow, perm: string): boolean {
    return row.permissions.includes(perm);
  }

  togglePermission(row: PermissionRow, perm: string, checked: boolean): void {
    const perms = new Set(row.permissions);
    if (checked) {
      perms.add(perm);
    } else {
      perms.delete(perm);
    }
    row.permissions = [...perms];
  }

  save(row: PermissionRow): void {
    // TODO: Implement permission saving for person architecture
  }
}
