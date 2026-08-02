import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import Quill from 'quill';
import { QuillModule } from 'ngx-quill';
import { LandingPageService } from '../../shared/services/landing-page.service';
import { NotificationService } from '../../shared/services/notification.service';
import { LandingContext, LandingPlaceholder } from '../../shared/models/landing-page.model';
import {
  pillSpan,
  pillsToTokens,
  renderWithContext,
  tokensToPills,
} from '../../shared/landing-token.util';
import { WEB_QUILL_TOOLBAR, configureQuillForWebOutput } from './quill-web.config';

const DRAG_MIME = 'application/x-landing-token';

/** Deutsche Überschriften der Token-Familien. */
const GROUP_LABELS: Record<string, string> = {
  person: 'Person',
  stunden: 'Stunden',
  kochdienst: 'Kochdienst',
};

interface PlaceholderGroup {
  group: string;
  label: string;
  tiles: LandingPlaceholder[];
}

@Component({
  selector: 'app-landing-page-editor',
  standalone: true,
  imports: [
    CommonModule, FormsModule, ReactiveFormsModule,
    MatButtonModule, MatIconModule, MatTabsModule, MatTooltipModule,
    QuillModule,
  ],
  templateUrl: './landing-page-editor.component.html',
  styleUrl: './landing-page-editor.component.scss',
})
export class LandingPageEditorComponent implements OnInit {
  readonly quillModules = { toolbar: WEB_QUILL_TOOLBAR };

  placeholders: LandingPlaceholder[] = [];
  groupedPlaceholders: PlaceholderGroup[] = [];
  quillInstance: any = null;
  sourceMode = false;
  sourceHtml = '';
  context: LandingContext = {};
  previewHtml: SafeHtml;

  form = new FormGroup({
    bodyHtml: new FormControl(''),
  });

  constructor(
    private landingPageService: LandingPageService,
    private notify: NotificationService,
    private sanitizer: DomSanitizer,
  ) {
    configureQuillForWebOutput();
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml('');
  }

  ngOnInit(): void {
    // Erst die Kacheln, dann der Inhalt: tokensToPills braucht die
    // Beschriftungen, sonst stünde der rohe Token in der Pille.
    this.landingPageService.placeholders().subscribe((tiles) => {
      this.placeholders = tiles;
      this.groupedPlaceholders = this.groupTiles(tiles);
      this.loadContent();
    });

    // Fällt der Kontext aus, bleibt die Vorschau nutzbar — renderWithContext
    // setzt für fehlende Werte einen Gedankenstrich.
    this.landingPageService.context().subscribe({
      next: (values) => (this.context = values),
      error: () => (this.context = {}),
    });
  }

  /** Baut die Vorschau aus dem aktuell bearbeiteten Inhalt neu auf. */
  refreshPreview(): void {
    const stored = this.sourceMode
      ? this.sourceHtml
      : pillsToTokens(this.form.value.bodyHtml ?? '');
    this.previewHtml = this.sanitizer.bypassSecurityTrustHtml(
      renderWithContext(stored, this.context),
    );
  }

  private loadContent(): void {
    this.landingPageService.get().subscribe((page) => {
      this.form.patchValue({
        bodyHtml: tokensToPills(page.bodyHtml ?? '', this.placeholders),
      });
    });
  }

  onEditorCreated(editor: any): void {
    this.quillInstance = editor;
  }

  private groupTiles(tiles: LandingPlaceholder[]): PlaceholderGroup[] {
    const byGroup = new Map<string, LandingPlaceholder[]>();
    tiles.forEach((tile) => {
      const list = byGroup.get(tile.group) ?? [];
      list.push(tile);
      byGroup.set(tile.group, list);
    });
    return Array.from(byGroup.entries()).map(([group, groupTiles]) => ({
      group,
      label: GROUP_LABELS[group] ?? group,
      tiles: groupTiles,
    }));
  }

  private insertPillAt(index: number, tile: LandingPlaceholder): void {
    this.quillInstance.insertEmbed(index, 'mail-token', { token: tile.token, label: tile.label });
    this.quillInstance.setSelection(index + 1, 0);
    this.form.patchValue({ bodyHtml: this.quillInstance.root?.innerHTML ?? '' });
  }

  /** Einfügen an der Cursorposition; ohne lebenden Editor wird angehängt. */
  insertPlaceholder(tile: LandingPlaceholder): void {
    if (this.quillInstance) {
      const selection = this.quillInstance.getSelection?.();
      const index = selection ? selection.index : this.quillInstance.getLength();
      this.insertPillAt(index, tile);
    } else {
      const current = this.form.value.bodyHtml ?? '';
      this.form.patchValue({ bodyHtml: current + pillSpan(tile.token, tile.label) });
    }
  }

  onChipDragStart(event: DragEvent, tile: LandingPlaceholder): void {
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
    const tile = this.placeholders.find((p) => p.token === token);
    if (!tile) {
      return;
    }
    event.preventDefault();
    this.insertPillAt(this.dropIndex(event), tile);
  }

  /** Bestmögliche Cursorposition aus dem Drop-Punkt; sonst ans Dokumentende. */
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

  /**
   * Wechselt zwischen WYSIWYG und Quelltext. Beide Ansichten arbeiten auf
   * demselben Inhalt, nur in unterschiedlicher Darstellung: im Editor als
   * Pillen, im Quelltext als rohe Tokens.
   */
  toggleSourceMode(): void {
    if (this.sourceMode) {
      this.form.patchValue({ bodyHtml: tokensToPills(this.sourceHtml, this.placeholders) });
      this.sourceMode = false;
    } else {
      this.sourceHtml = pillsToTokens(this.form.value.bodyHtml ?? '');
      this.sourceMode = true;
    }
  }

  save(): void {
    // Im Quelltextmodus ist sourceHtml die Wahrheit, sonst das Formularfeld.
    const bodyHtml = this.sourceMode
      ? this.sourceHtml
      : pillsToTokens(this.form.value.bodyHtml ?? '');
    this.landingPageService.save(bodyHtml).subscribe({
      next: () => this.notify.success('Startseite gespeichert'),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
