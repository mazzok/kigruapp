import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';

@Component({
  selector: 'app-mail-template-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule, MatListModule,
    QuillModule,
  ],
  templateUrl: './mail-template-editor.component.html',
  styleUrl: './mail-template-editor.component.scss',
})
export class MailTemplateEditorComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  templates: MailTemplate[] = [];
  selectedId: string | null = null;
  placeholders: PlaceholderTile[] = [];

  /** Set via the (onEditorCreated) output on <quill-editor>; null until the editor renders. */
  quillInstance: any = null;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(private mailTemplateService: MailTemplateService) {
    configureQuillForEmailSafeOutput();
  }

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.placeholders().subscribe((tiles) => {
      this.placeholders = tiles;
    });
  }

  load(): void {
    this.mailTemplateService.list().subscribe((templates) => {
      this.templates = templates;
    });
  }

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  /** Inserts a placeholder token at the current cursor position (or appends if no selection). */
  insertPlaceholder(tile: PlaceholderTile): void {
    const current = this.form.value.bodyHtml ?? '';
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.quillInstance.insertText(index, tile.token);
      const html = this.quillInstance.root?.innerHTML;
      this.form.patchValue({ bodyHtml: html !== undefined ? html : current + tile.token });
    } else {
      this.form.patchValue({ bodyHtml: current + tile.token });
    }
  }

  selectForEdit(template: MailTemplate): void {
    this.selectedId = template.id;
    this.form.patchValue({ name: template.name, bodyHtml: template.bodyHtml });
  }

  newTemplate(): void {
    this.selectedId = null;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  save(): void {
    const request = {
      name: this.form.value.name ?? '',
      bodyHtml: this.form.value.bodyHtml ?? '',
    };
    const save$ = this.selectedId
      ? this.mailTemplateService.update(this.selectedId, request)
      : this.mailTemplateService.create(request);
    save$.subscribe(() => {
      this.newTemplate();
      this.load();
    });
  }

  delete(template: MailTemplate): void {
    this.mailTemplateService.delete(template.id).subscribe(() => {
      if (this.selectedId === template.id) {
        this.newTemplate();
      }
      this.load();
    });
  }
}
