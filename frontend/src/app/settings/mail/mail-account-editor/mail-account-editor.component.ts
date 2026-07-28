import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount, MailEncryption, SaveMailAccountRequest } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';

@Component({
  selector: 'app-mail-account-editor',
  standalone: true,
  imports: [
    CommonModule, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatSlideToggleModule, MatIconModule, MatTooltipModule,
  ],
  templateUrl: './mail-account-editor.component.html',
  styleUrl: './mail-account-editor.component.scss',
})
export class MailAccountEditorComponent implements OnInit {
  accounts: MailAccount[] = [];
  selectedId: string | null = null;
  /** When false the form is hidden and a placeholder is shown instead. */
  editing = false;
  /** Whether the selected account has a stored password (drives the hint). */
  passwordSet = false;

  readonly encryptionOptions: MailEncryption[] = ['NONE', 'STARTTLS', 'SSL_TLS'];

  form = new FormGroup({
    name: new FormControl('', Validators.required),
    host: new FormControl('', Validators.required),
    port: new FormControl<number>(587, Validators.required),
    encryption: new FormControl<MailEncryption>('STARTTLS', { nonNullable: true }),
    username: new FormControl(''),
    password: new FormControl(''),
    fromAddress: new FormControl('', Validators.required),
    fromName: new FormControl(''),
    enabled: new FormControl(false, { nonNullable: true }),
  });

  constructor(
    private mailAccountService: MailAccountService,
    private notify: NotificationService,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.mailAccountService.list().subscribe((accounts) => (this.accounts = accounts));
  }

  selectForEdit(account: MailAccount): void {
    this.selectedId = account.id;
    this.editing = true;
    this.passwordSet = account.passwordSet;
    this.form.reset({
      name: account.name, host: account.host, port: account.port,
      encryption: account.encryption, username: account.username, password: '',
      fromAddress: account.fromAddress, fromName: account.fromName, enabled: account.enabled,
    });
  }

  newAccount(): void {
    this.selectedId = null;
    this.editing = true;
    this.passwordSet = false;
    this.form.reset({
      name: '', host: '', port: 587, encryption: 'STARTTLS', username: '',
      password: '', fromAddress: '', fromName: '', enabled: false,
    });
  }

  closeEditor(): void {
    this.selectedId = null;
    this.editing = false;
    this.passwordSet = false;
    this.form.reset({
      name: '', host: '', port: 587, encryption: 'STARTTLS', username: '',
      password: '', fromAddress: '', fromName: '', enabled: false,
    });
  }

  save(): void {
    const v = this.form.value;
    const request: SaveMailAccountRequest = {
      name: v.name ?? '',
      host: v.host ?? '',
      port: v.port ?? 0,
      encryption: v.encryption ?? 'NONE',
      username: v.username ?? '',
      fromAddress: v.fromAddress ?? '',
      fromName: v.fromName ?? '',
      enabled: v.enabled ?? false,
    };
    if (v.password && v.password.trim().length > 0) {
      request.password = v.password;
    }
    const isUpdate = this.selectedId !== null;
    const save$ = this.selectedId
      ? this.mailAccountService.update(this.selectedId, request)
      : this.mailAccountService.create(request);
    save$.subscribe({
      next: () => {
        this.notify.success(isUpdate ? 'Konto aktualisiert' : 'Konto gespeichert');
        this.closeEditor();
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }

  /** Inline list toggle: persist the enabled flag immediately, keeping the stored password. */
  toggleEnabled(account: MailAccount): void {
    const request: SaveMailAccountRequest = {
      name: account.name, host: account.host, port: account.port,
      encryption: account.encryption, username: account.username,
      fromAddress: account.fromAddress, fromName: account.fromName,
      enabled: !account.enabled,
    };
    this.mailAccountService.update(account.id, request).subscribe({
      next: () => {
        this.notify.success(account.enabled ? 'Konto deaktiviert' : 'Konto aktiviert');
        this.load();
      },
      error: (err) => {
        this.notify.error(this.notify.extractError(err));
        this.load(); // revert the optimistic toggle to the server state
      },
    });
  }

  delete(account: MailAccount): void {
    this.mailAccountService.delete(account.id).subscribe({
      next: () => {
        this.notify.success('Konto gelöscht');
        if (this.selectedId === account.id) {
          this.closeEditor();
        }
        this.load();
      },
      error: (err) => this.notify.error(this.notify.extractError(err)),
    });
  }
}
