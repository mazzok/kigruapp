import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplate, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';
import { tokensToPills, pillsToTokens, pillSpan, renderPreview, SAMPLE_VALUES } from './mail-token.util';
import { NotificationService } from '../../../shared/services/notification.service';

const DRAG_MIME = 'application/x-mail-token';

@Component({
  selector: 'app-mail-template-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatButtonModule, MatSelectModule,
    MatIconModule, MatTooltipModule,
    QuillModule,
  ],
  templateUrl: './mail-template-editor.component.html',
  styleUrl: './mail-template-editor.component.scss',
})
export class MailTemplateEditorComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  templates: MailTemplate[] = [];
  selectedId: string | null = null;
  /** When false the editor is hidden and a placeholder is shown instead. */
  editing = false;
  placeholders: PlaceholderTile[] = [];
  previewHtml: SafeHtml;

  quillInstance: any = null;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
    private notify: NotificationService,
  ) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    this.load();
    this.mailTemplateService.placeholders().subscribe((tiles) => (this.placeholders = tiles));
    this.form.controls.bodyHtml.valueChanges.subscribe((v) => this.updatePreview(v ?? ''));
  }

  load(): void {
    this.mailTemplateService.list().subscribe((templates) => (this.templates = templates));
  }

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  private labelFor(tile: PlaceholderTile): string {
    return tile.label['de'] || tile.fieldName;
  }

  private syncBodyFromQuill(): void {
    this.form.patchValue({ bodyHtml: this.quillInstance.root?.innerHTML ?? '' });
  }

  private insertPillAt(index: number, tile: PlaceholderTile): void {
    this.quillInstance.insertEmbed(index, 'mail-token', { token: tile.token, label: this.labelFor(tile) });
    this.quillInstance.setSelection(index + 1, 0);
    this.syncBodyFromQuill();
  }

  /** Click-insert at the cursor (or append if there is no live editor yet). */
  insertPlaceholder(tile: PlaceholderTile): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertPillAt(index, tile);
    } else {
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + pillSpan(tile.token, this.labelFor(tile)) });
    }
  }

  onChipDragStart(event: DragEvent, tile: PlaceholderTile): void {
    event.dataTransfer?.setData(DRAG_MIME, tile.token);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onEditorDrop(event: DragEvent): void {
    const token = event.dataTransfer?.getData(DRAG_MIME);
    if (!token || !this.quillInstance) {
      return;
    }
    event.preventDefault();
    const tile = this.placeholders.find((p) => p.token === token);
    if (!tile) {
      return;
    }
    this.insertPillAt(this.dropIndex(event), tile);
  }

  /** Best-effort caret index from the drop point; falls back to the document end. */
  private dropIndex(event: DragEvent): number {
    const end = Math.max(0, this.quillInstance.getLength() - 1);
    try {
      const doc: any = document;
      const range = doc.caretRangeFromPoint?.(event.clientX, event.clientY);
      if (!range) {
        return end;
      }
      const blot = Quill.find(range.startContainer, true);
      if (!blot) {
        return end;
      }
      return this.quillInstance.getIndex(blot) + range.startOffset;
    } catch {
      return end;
    }
  }

  private updatePreview(editorHtml: string): void {
    const rendered = renderPreview(pillsToTokens(editorHtml), SAMPLE_VALUES);
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(rendered);
  }

  get selectedTemplate(): MailTemplate | undefined {
    return this.templates.find((t) => t.id === this.selectedId);
  }

  onSelectTemplate(id: string): void {
    const template = this.templates.find((t) => t.id === id);
    if (!template) {
      return;
    }
    this.selectedId = template.id;
    this.editing = true;
    this.form.patchValue({
      name: template.name,
      bodyHtml: tokensToPills(template.bodyHtml, this.placeholders),
    });
  }

  /** Convenience for the sidebar list-item click. */
  selectForEdit(template: MailTemplate): void {
    this.onSelectTemplate(template.id);
  }

  newTemplate(): void {
    this.selectedId = null;
    this.editing = true;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  /** Close the editor and return to the placeholder (no template selected). */
  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.form.reset({ name: '', bodyHtml: '' });
  }

  save(): void {
    const request = {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(this.form.value.bodyHtml ?? ''),
    };
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
