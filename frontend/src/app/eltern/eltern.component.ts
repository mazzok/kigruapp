import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ParentDirectoryService } from './services/parent-directory.service';
import { NotificationService } from '../shared/services/notification.service';
import { ParentDirectoryGroup } from '../shared/models/parent-directory.model';

@Component({
  selector: 'app-eltern',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatFormFieldModule, MatSelectModule, MatButtonModule, MatIconModule,
  ],
  templateUrl: './eltern.component.html',
  styleUrl: './eltern.component.scss',
})
export class ElternComponent implements OnInit {
  groups: ParentDirectoryGroup[] = [];
  selectedGroupId: string | null = null;
  loading = false;
  failed = false;

  constructor(
    private directory: ParentDirectoryService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.failed = false;
    this.directory.load().subscribe({
      next: (result) => {
        this.groups = result.groups;
        this.selectedGroupId = result.groups.length > 0 ? result.groups[0].groupInstanceId : null;
        this.loading = false;
      },
      error: (err) => {
        this.groups = [];
        this.selectedGroupId = null;
        this.loading = false;
        this.failed = true;
        this.notify.error(this.notify.extractError(err));
      },
    });
  }

  get selectedGroup(): ParentDirectoryGroup | null {
    return this.groups.find((g) => g.groupInstanceId === this.selectedGroupId) ?? null;
  }

  selectGroup(groupInstanceId: string): void {
    this.selectedGroupId = groupInstanceId;
  }

  parentName(parent: { firstName: string | null; lastName: string | null }): string {
    return [parent.firstName, parent.lastName].filter((part) => !!part).join(' ');
  }
}
