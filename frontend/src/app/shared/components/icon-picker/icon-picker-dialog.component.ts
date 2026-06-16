import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MATERIAL_ICONS } from './material-icons.const';

@Component({
  selector: 'app-icon-picker-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatDialogModule, MatFormFieldModule,
    MatInputModule, MatIconModule, MatButtonModule,
  ],
  templateUrl: './icon-picker-dialog.component.html',
})
export class IconPickerDialogComponent implements OnInit {
  searchTerm = '';
  filteredIcons: readonly string[] = MATERIAL_ICONS;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private dialogRef: MatDialogRef<IconPickerDialogComponent>) {}

  ngOnInit(): void {
    this.filteredIcons = MATERIAL_ICONS;
  }

  onSearch(term: string): void {
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => {
      const lower = term.toLowerCase().trim();
      this.filteredIcons = lower
        ? MATERIAL_ICONS.filter(name => name.includes(lower))
        : MATERIAL_ICONS;
    }, 200);
  }

  select(iconName: string): void {
    this.dialogRef.close(iconName);
  }

  cancel(): void {
    this.dialogRef.close();
  }
}
