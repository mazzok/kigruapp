import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MailAccountEditorComponent } from './mail-account-editor/mail-account-editor.component';
import { MailTemplateEditorComponent } from './mail-template-editor/mail-template-editor.component';
import { MailJobEditorComponent } from './mail-job-editor/mail-job-editor.component';

@Component({
  selector: 'app-mail',
  standalone: true,
  imports: [
    CommonModule, MatTabsModule,
    MailAccountEditorComponent, MailTemplateEditorComponent, MailJobEditorComponent,
  ],
  templateUrl: './mail.component.html',
  styleUrl: './mail.component.scss',
})
export class MailComponent {}
