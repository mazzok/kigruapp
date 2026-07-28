import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MailTemplateEditorComponent } from './mail-template-editor/mail-template-editor.component';
import { MailJobEditorComponent } from './mail-job-editor/mail-job-editor.component';
import { MailSettingsService } from '../../shared/services/mail-settings.service';
import { NotificationService } from '../../shared/services/notification.service';
import {
  MailEncryption,
  MailTestResult,
  UpdateMailSettingsRequest,
} from '../../shared/models/mail-settings.model';

@Component({
  selector: 'app-mail',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatSlideToggleModule, MatTabsModule,
    MailTemplateEditorComponent, MailJobEditorComponent,
  ],
  templateUrl: './mail.component.html',
  styleUrl: './mail.component.scss',
})
export class MailComponent implements OnInit {
  passwordSet = false;
  testResult: MailTestResult | null = null;

  readonly encryptionOptions: MailEncryption[] = ['NONE', 'STARTTLS', 'SSL_TLS'];

  form = new FormGroup({
    host: new FormControl('', Validators.required),
    port: new FormControl<number>(587, Validators.required),
    encryption: new FormControl<MailEncryption>('STARTTLS', Validators.required),
    username: new FormControl(''),
    password: new FormControl(''),
    fromAddress: new FormControl('', Validators.required),
    fromName: new FormControl(''),
    enabled: new FormControl(false),
  });

  testRecipient = new FormControl('');

  constructor(
    private mailSettingsService: MailSettingsService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.mailSettingsService.get().subscribe((s) => {
      this.passwordSet = s.passwordSet;
      this.form.patchValue({
        host: s.host,
        port: s.port,
        encryption: s.encryption,
        username: s.username,
        fromAddress: s.fromAddress,
        fromName: s.fromName,
        enabled: s.enabled,
      });
      // password field intentionally stays empty; "gesetzt"-Anzeige kommt aus passwordSet
    });
  }

  save(): void {
    const v = this.form.value;
    const request: UpdateMailSettingsRequest = {
      host: v.host ?? '',
      port: v.port ?? 0,
      encryption: v.encryption ?? 'NONE',
      username: v.username ?? '',
      fromAddress: v.fromAddress ?? '',
      fromName: v.fromName ?? '',
      enabled: v.enabled ?? false,
    };
    const password = v.password;
    if (password && password.trim().length > 0) {
      request.password = password;
    }
    this.mailSettingsService.update(request).subscribe({
      next: (s) => {
        this.passwordSet = s.passwordSet;
        this.form.get('password')!.reset('');
        this.notify.success('Einstellungen gespeichert');
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  sendTest(): void {
    const recipient = this.testRecipient.value ?? '';
    this.mailSettingsService.test(recipient).subscribe({
      next: (result) => {
        this.testResult = result;
        if (result.success) {
          this.notify.success('Testmail gesendet');
        } else {
          this.notify.error(result.message ?? 'Testmail fehlgeschlagen');
        }
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
