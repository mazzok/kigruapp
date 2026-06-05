import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { ParentService } from '../../administration/families/services/parent.service';
import { ApiService } from '../../core/services/api.service';
import { Parent } from '../../shared/models/parent.model';

const AVAILABLE_PERMISSIONS = [
  'families.read',
  'families.write',
  'settings.admin',
  'permissions.manage',
];

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
  dataSource = new MatTableDataSource<Parent>();

  constructor(private api: ApiService, private parentService: ParentService) {}

  ngOnInit(): void {
    this.loadParents();
  }

  loadParents(): void {
    this.api.get<Parent[]>('/parents').subscribe((parents) => {
      this.dataSource.data = parents;
    });
  }

  hasPermission(parent: Parent, perm: string): boolean {
    return parent.permissions?.includes(perm) ?? false;
  }

  togglePermission(parent: Parent, perm: string, checked: boolean): void {
    const perms = new Set(parent.permissions ?? []);
    if (checked) {
      perms.add(perm);
    } else {
      perms.delete(perm);
    }
    parent.permissions = [...perms];
  }

  save(parent: Parent): void {
    this.parentService.update(parent.id!, parent).subscribe();
  }
}
