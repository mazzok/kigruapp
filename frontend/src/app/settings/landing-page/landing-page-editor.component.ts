import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { QuillModule } from 'ngx-quill';
import { LandingPageService } from '../../shared/services/landing-page.service';
import { NotificationService } from '../../shared/services/notification.service';
import { LandingPlaceholder } from '../../shared/models/landing-page.model';
import { pillsToTokens, tokensToPills } from '../../shared/landing-token.util';
import { WEB_QUILL_TOOLBAR, configureQuillForWebOutput } from './quill-web.config';

@Component({
  selector: 'app-landing-page-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatButtonModule, MatIconModule, MatTabsModule, MatTooltipModule,
    QuillModule,
  ],
  templateUrl: './landing-page-editor.component.html',
  styleUrl: './landing-page-editor.component.scss',
})
export class LandingPageEditorComponent implements OnInit {
  readonly quillModules = { toolbar: WEB_QUILL_TOOLBAR };

  placeholders: LandingPlaceholder[] = [];
  quillInstance: any = null;

  form = new FormGroup({
    bodyHtml: new FormControl(''),
  });

  constructor(
    private landingPageService: LandingPageService,
    private notify: NotificationService,
  ) {
    configureQuillForWebOutput();
  }

  ngOnInit(): void {
    // Erst die Kacheln, dann der Inhalt: tokensToPills braucht die
    // Beschriftungen, sonst stünde der rohe Token in der Pille.
    this.landingPageService.placeholders().subscribe((tiles) => {
      this.placeholders = tiles;
      this.loadContent();
    });
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

  save(): void {
    const bodyHtml = pillsToTokens(this.form.value.bodyHtml ?? '');
    this.landingPageService.save(bodyHtml).subscribe({
      next: () => this.notify.success('Startseite gespeichert'),
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
