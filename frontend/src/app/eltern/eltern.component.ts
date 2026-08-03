import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ParentDirectoryService } from './services/parent-directory.service';
import { NotificationService } from '../shared/services/notification.service';
import { ParentDirectoryColumn, ParentDirectoryGroup } from '../shared/models/parent-directory.model';

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
  columns: ParentDirectoryColumn[] = [];
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
        this.columns = result.columns ?? [];
        this.selectedGroupId = result.groups.length > 0 ? result.groups[0].groupInstanceId : null;
        this.loading = false;
      },
      error: (err) => {
        this.groups = [];
        this.columns = [];
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

  get parentColumns(): ParentDirectoryColumn[] {
    return this.columns.filter((c) => c.scope === 'PARENT');
  }

  get showAddress(): boolean {
    return this.columns.some((c) => c.key === 'address');
  }

  get showEntryDate(): boolean {
    return this.columns.some((c) => c.key === 'childEntryDate');
  }

  get showExitDate(): boolean {
    return this.columns.some((c) => c.key === 'childExitDate');
  }

  /** mailto:/tel:-Verweis fuer die beiden Kontaktspalten, sonst reiner Text. */
  linkFor(key: string, value: string): string | null {
    if (key === 'email') return `mailto:${value}`;
    if (key === 'phone') return `tel:${value}`;
    return null;
  }
}
