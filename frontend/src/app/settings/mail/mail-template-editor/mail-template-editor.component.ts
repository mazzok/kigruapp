import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate } from '../../../shared/models/mail-template.model';
import { NotificationService } from '../../../shared/services/notification.service';
import { MailTemplateFormComponent } from './mail-template-form.component';

@Component({
  selector: 'app-mail-template-editor',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule, MatIconModule, MatTooltipModule,
    MailTemplateFormComponent,
  ],
  templateUrl: './mail-template-editor.component.html',
  styleUrl: './mail-template-editor.component.scss',
})
export class MailTemplateEditorComponent implements OnInit {
  templates: MailTemplate[] = [];
  selectedId: string | null = null;
  /** When false the editor is hidden and a placeholder is shown instead. */
  editing = false;

  /** Aktueller Wert der eingebetteten Maske, in Token-Form. */
  formValue = { name: '', bodyHtml: '' };
  editorValue = { name: '', bodyHtml: '' };
  formValid = false;

  constructor(
    private mailTemplateService: MailTemplateService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
  }

  onFormValueChange(value: { name: string; bodyHtml: string }): void {
    this.formValue = value;
    this.formValid = value.name.trim().length > 0 && value.bodyHtml.trim().length > 0;
  }

  get selectedTemplate(): MailTemplate | undefined {
    return this.templates.find((t) => t.id === this.selectedId);
  }

  onSelectTemplate(id: string): void {
    const template = this.templates.find((t) => t.id === id);
    if (!template || template.kind === 'COOKING') {
      return;
    }
    this.selectedId = template.id;
    this.editing = true;
    this.editorValue = { name: template.name, bodyHtml: template.bodyHtml };
  }

  /** Convenience for the sidebar list-item click. */
  selectForEdit(template: MailTemplate): void {
    this.onSelectTemplate(template.id);
  }

  newTemplate(): void {
    this.selectedId = null;
    this.editing = true;
    this.editorValue = { name: '', bodyHtml: '' };
  }

  /** Close the editor and return to the placeholder (no template selected). */
  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.editorValue = { name: '', bodyHtml: '' };
  }

  save(): void {
    const request = { name: this.formValue.name, bodyHtml: this.formValue.bodyHtml };
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.mailTemplateService.update(this.selectedId, request)
      : this.mailTemplateService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Vorlage aktualisiert' : 'Vorlage gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  isCooking(template: MailTemplate): boolean {
    return template.kind === 'COOKING';
  }

  delete(template: MailTemplate): void {
    this.mailTemplateService.delete(template.id).subscribe({
      next: () => {
        this.notify.success('Vorlage gelöscht');
        if (this.selectedId === template.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
