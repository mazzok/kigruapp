import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MailAccountEditorComponent } from './mail-account-editor.component';
import { MailAccountService } from '../../../shared/services/mail-account.service';
import { MailAccount, SaveMailAccountRequest } from '../../../shared/models/mail-account.model';
import { NotificationService } from '../../../shared/services/notification.service';

class FakeMailAccountService {
  accounts: MailAccount[] = [{
    id: 'a1', name: 'Haupt', host: 'smtp.example.test', port: 587, encryption: 'STARTTLS',
    username: 'user', fromAddress: 'kita@example.test', fromName: 'Kita', enabled: true, passwordSet: true,
  }];
  createCalls: SaveMailAccountRequest[] = [];
  updateCalls: { id: string; request: SaveMailAccountRequest }[] = [];
  deleteCalls: string[] = [];

  list() { return of(this.accounts); }
  create(request: SaveMailAccountRequest) {
    this.createCalls.push(request);
    return of({ id: 'a2', ...request, passwordSet: !!request.password } as MailAccount);
  }
  update(id: string, request: SaveMailAccountRequest) {
    this.updateCalls.push({ id, request });
    return of({ id, ...request, passwordSet: true } as MailAccount);
  }
  delete(id: string) { this.deleteCalls.push(id); return of(undefined); }
}

class FakeNotificationService {
  successCalls: string[] = [];
  errorCalls: string[] = [];
  success(m: string) { this.successCalls.push(m); }
  error(m: string) { this.errorCalls.push(m); }
  extractError(err: unknown) { return err instanceof HttpErrorResponse ? String(err.error) : 'error'; }
}

describe('MailAccountEditorComponent', () => {
  let component: MailAccountEditorComponent;
  let service: FakeMailAccountService;
  let notify: FakeNotificationService;

  beforeEach(() => {
    service = new FakeMailAccountService();
    notify = new FakeNotificationService();
    component = new MailAccountEditorComponent(
      service as unknown as MailAccountService,
      notify as unknown as NotificationService,
    );
    component.ngOnInit();
  });

  it('lists accounts and starts with the editor closed', () => {
    expect(component.accounts.length).toBe(1);
    expect(component.editing).toBe(false);
  });

  it('opens the editor via newAccount and selectForEdit; closeEditor hides it', () => {
    component.newAccount();
    expect(component.editing).toBe(true);
    expect(component.selectedId).toBeNull();

    component.closeEditor();
    expect(component.editing).toBe(false);

    component.selectForEdit(service.accounts[0]);
    expect(component.editing).toBe(true);
    expect(component.selectedId).toBe('a1');
    expect(component.form.value.name).toBe('Haupt');
  });

  it('creates a new account and closes the editor', () => {
    component.newAccount();
    component.form.patchValue({
      name: 'Neu', host: 'h', port: 25, encryption: 'NONE',
      username: '', fromAddress: 'a@b.test', fromName: '', enabled: true,
    });

    component.save();

    expect(service.createCalls.length).toBe(1);
    expect(service.createCalls[0].name).toBe('Neu');
    expect(component.editing).toBe(false);
    expect(notify.successCalls).toEqual(['Konto gespeichert']);
  });

  it('inline toggle persists the flipped enabled flag immediately, keeping the password', () => {
    // account a1 is enabled with a stored password -> toggling disables it, no password sent
    component.toggleEnabled(service.accounts[0]);

    expect(service.updateCalls.length).toBe(1);
    expect(service.updateCalls[0].id).toBe('a1');
    expect(service.updateCalls[0].request.enabled).toBe(false);
    expect(service.updateCalls[0].request.password).toBeUndefined();
    expect(notify.successCalls).toEqual(['Konto deaktiviert']);
  });

  it('only sends the password when the field has a value', () => {
    component.selectForEdit(service.accounts[0]);
    component.save();
    expect(service.updateCalls[0].request.password).toBeUndefined();

    component.selectForEdit(service.accounts[0]);
    component.form.patchValue({ password: 'newpw' });
    component.save();
    expect(service.updateCalls[1].request.password).toBe('newpw');
  });

  it('surfaces the backend reason on save failure', () => {
    service.create = () => throwError(() => new HttpErrorResponse({ status: 400, error: 'host must not be empty' }));
    component.newAccount();
    component.save();
    expect(notify.errorCalls).toEqual(['host must not be empty']);
  });

  it('delete calls the service and returns to the placeholder when the open account is deleted', () => {
    component.selectForEdit(service.accounts[0]);
    component.delete(service.accounts[0]);
    expect(service.deleteCalls).toEqual(['a1']);
    expect(component.editing).toBe(false);
  });
});
