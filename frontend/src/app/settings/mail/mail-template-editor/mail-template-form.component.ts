import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { MailTemplateService } from '../../../shared/services/mail-template.service';
import { MailTemplateKind, PlaceholderTile } from '../../../shared/models/mail-template.model';
import { configureQuillForEmailSafeOutput, EMAIL_SAFE_QUILL_TOOLBAR } from './quill-email-safe.config';
import { tokensToPills, pillsToTokens, pillSpan, renderPreview, SAMPLE_VALUES } from './mail-token.util';
import { blockSpan, cookingDutyBlockSummary, instanceLabel } from './mail-block.util';
import {
  blockDefinitionsForKind, DEFAULT_BLOCK_CONFIG, MailBlockDefinition, MailBlockConfig, CookingDutyBlockConfig,
} from '../../../shared/models/mail-block.model';
import { FieldInstanceDTO } from '../../../shared/models/field-instance.model';

const DRAG_MIME = 'application/x-mail-token';
const BLOCK_DRAG_MIME = 'application/x-mail-block';

/** Eine Chip-Gruppe mit ihrer Ueberschrift. */
export interface PlaceholderGroup {
  label: string;
  tiles: PlaceholderTile[];
}

/**
 * Die reine Vorlagen-Maske: Name, gruppierte Platzhalter-Chips, Editor und
 * Vorschau. Speichert nicht selbst — der einbettende Bereich entscheidet, wohin
 * der Wert geht (allgemeine Vorlagen oder Kochdienst-Job).
 */
@Component({
  selector: 'app-mail-template-form',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatIconModule,
    QuillModule,
  ],
  templateUrl: './mail-template-form.component.html',
  styleUrl: './mail-template-form.component.scss',
})
export class MailTemplateFormComponent implements OnInit {
  readonly quillModules = { toolbar: EMAIL_SAFE_QUILL_TOOLBAR };

  @Input() kind: MailTemplateKind = 'GENERAL';
  @Input() nameLabel = 'Name';

  /** Wert in Token-Form; wird beim Setzen in Pill-Form uebersetzt. */
  @Input() set value(v: { name: string; bodyHtml: string }) {
    this.lastRawValue = v;
    this.applyValue(v);
  }

  @Output() valueChange = new EventEmitter<{ name: string; bodyHtml: string }>();

  placeholders: PlaceholderTile[] = [];
  groups: PlaceholderGroup[] = [];
  previewHtml: SafeHtml;
  quillInstance: any = null;

  /** Feldinstanzen fuer die Gruppen-Auswahl im Block-Konfigurations-Dialog. Nur bei COOKING_OVERVIEW geladen (Task 4). */
  fieldInstanceGroups: FieldInstanceDTO[] = [];

  /**
   * Letzter über das Input gesetzter Wert in Token-Form. Wird erneut
   * angewendet, sobald die Platzhalter asynchron eintreffen — der Setter kann
   * vor ngOnInit feuern (Angular-Bindungsreihenfolge), wenn `placeholders`
   * noch leer ist, und würde sonst rohe Tokens statt Pill-Labels anzeigen.
   */
  private lastRawValue: { name: string; bodyHtml: string } | null = null;

  /** True, sobald die Platzhalter mindestens einmal geladen wurden. */
  private placeholdersLoaded = false;

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    bodyHtml: new FormControl('', Validators.required),
  });

  constructor(
    private mailTemplateService: MailTemplateService,
    private sanitizer: DomSanitizer,
  ) {
    configureQuillForEmailSafeOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    this.mailTemplateService.placeholders(this.kind).subscribe((tiles) => {
      this.placeholders = tiles;
      this.groups = this.buildGroups(tiles);
      this.placeholdersLoaded = true;
      if (this.lastRawValue) {
        this.applyValue(this.lastRawValue);
      }
    });
    this.form.valueChanges.subscribe(() => {
      this.updatePreview(this.form.value.bodyHtml ?? '');
      this.valueChange.emit(this.currentValue());
    });
  }

  private applyValue(v: { name: string; bodyHtml: string }): void {
    const bodyHtml = this.placeholdersLoaded
      ? tokensToPills(v.bodyHtml, this.placeholders)
      : v.bodyHtml;
    this.form.patchValue({ name: v.name, bodyHtml }, { emitEvent: false });
    this.updatePreview(this.form.value.bodyHtml ?? '');
  }

  get valid(): boolean {
    return this.form.valid;
  }

  get blockDefinitions(): MailBlockDefinition[] {
    return blockDefinitionsForKind(this.kind);
  }

  currentValue(): { name: string; bodyHtml: string } {
    return {
      name: this.form.value.name ?? '',
      bodyHtml: pillsToTokens(this.form.value.bodyHtml ?? ''),
    };
  }

  /** Erhaelt die Reihenfolge, in der der Server die Kacheln liefert. */
  private buildGroups(tiles: PlaceholderTile[]): PlaceholderGroup[] {
    const groups: PlaceholderGroup[] = [];
    tiles.forEach((tile) => {
      const existing = groups.find((g) => g.label === tile.groupLabel);
      if (existing) {
        existing.tiles.push(tile);
      } else {
        groups.push({ label: tile.groupLabel, tiles: [tile] });
      }
    });
    return groups;
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

  private insertBlockAt(index: number, blockType: string): void {
    const config = DEFAULT_BLOCK_CONFIG[blockType];
    if (!config) {
      return;
    }
    const summary = this.summaryFor(blockType, config);
    this.quillInstance.insertEmbed(index, 'mail-block', { blockType, config, summary });
    this.quillInstance.setSelection(index + 1, 0);
    this.syncBodyFromQuill();
  }

  /** Click-insert at the cursor (or append if there is no live editor yet). */
  insertBlock(def: MailBlockDefinition): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertBlockAt(index, def.type);
    } else {
      const config = DEFAULT_BLOCK_CONFIG[def.type];
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + blockSpan(def.type, config, this.summaryFor(def.type, config)) });
    }
  }

  onBlockDragStart(event: DragEvent, def: MailBlockDefinition): void {
    event.dataTransfer?.setData(BLOCK_DRAG_MIME, def.type);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  /**
   * Resolves the human-readable card text for a block's current config. Only
   * `cookingDuty` exists today. `this.fieldInstanceGroups` is empty until
   * Task 4 wires group loading, so the group name falls back to "Gruppe
   * wählen" until then — expected, covered by Task 4's own tests.
   */
  private summaryFor(blockType: string, config: MailBlockConfig): string {
    if (blockType === 'cookingDuty') {
      const cfg = config as CookingDutyBlockConfig;
      const group = this.fieldInstanceGroups.find((g) => g.id === cfg.groupId);
      return cookingDutyBlockSummary(cfg, group ? instanceLabel(group) : null);
    }
    return 'Baustein';
  }

  onEditorDragOver(event: DragEvent): void {
    event.preventDefault();
  }

  onEditorDrop(event: DragEvent): void {
    const blockType = event.dataTransfer?.getData(BLOCK_DRAG_MIME);
    if (blockType && this.quillInstance) {
      event.preventDefault();
      this.insertBlockAt(this.dropIndex(event), blockType);
      return;
    }
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
}
