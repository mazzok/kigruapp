import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { NotificationService } from '../../../shared/services/notification.service';
import {
  ParentDirectoryAttribute,
  ParentDirectorySettingsService,
} from './parent-directory-settings.service';

/**
 * Globale Auswahl der Attribute, die die Eltern-Uebersicht zeigt. Gilt fuer alle
 * Gruppen und Semester; childName ist als Zeilenanker nicht abwaehlbar.
 */
@Component({
  selector: 'app-parent-directory-attributes',
  standalone: true,
  imports: [CommonModule, MatCheckboxModule],
  templateUrl: './parent-directory-attributes.component.html',
  styleUrl: './parent-directory-attributes.component.scss',
})
export class ParentDirectoryAttributesComponent implements OnInit {
  attributes: ParentDirectoryAttribute[] = [];
  saving = false;

  constructor(
    private settings: ParentDirectorySettingsService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.settings.load().subscribe({
      next: (catalog) => (this.attributes = catalog.attributes),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  get childAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'CHILD');
  }

  get parentAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'PARENT');
  }

  get familyAttributes(): ParentDirectoryAttribute[] {
    return this.attributes.filter((a) => a.scope === 'FAMILY');
  }

  toggle(attribute: ParentDirectoryAttribute, selected: boolean): void {
    if (attribute.locked) return;
    attribute.selected = selected;
    this.save();
  }

  save(): void {
    this.saving = true;
    const keys = this.attributes.filter((a) => a.selected).map((a) => a.key);
    this.settings.save(keys).subscribe({
      next: () => {
        this.saving = false;
        this.notify.success('Sichtbare Attribute gespeichert');
      },
      error: (err) => {
        this.saving = false;
        this.notify.error(this.notify.extractError(err));
      },
    });
  }
}
