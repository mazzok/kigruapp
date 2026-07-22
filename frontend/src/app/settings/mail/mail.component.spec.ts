import { of } from 'rxjs';
import { MailComponent } from './mail.component';
import { MailSettingsService } from '../../shared/services/mail-settings.service';
import {
  MailSettings,
  MailTestResult,
  UpdateMailSettingsRequest,
} from '../../shared/models/mail-settings.model';

class FakeMailSettingsService {
  settings: MailSettings = {
    host: 'smtp.example.test',
    port: 587,
    encryption: 'STARTTLS',
    username: 'mailer',
    fromAddress: 'kita@example.test',
    fromName: 'Kita',
    enabled: true,
    passwordSet: true,
  };
  updateCalls: UpdateMailSettingsRequest[] = [];
  testCalls: string[] = [];
  testResult: MailTestResult = { success: true, category: 'OK', message: 'ok' };

  get() {
    return of(this.settings);
  }
  update(request: UpdateMailSettingsRequest) {
    this.updateCalls.push(request);
    return of({ ...this.settings, passwordSet: request.password ? true : this.settings.passwordSet });
  }
  test(recipient: string) {
    this.testCalls.push(recipient);
    return of(this.testResult);
  }
}

describe('MailComponent', () => {
  let component: MailComponent;
  let service: FakeMailSettingsService;

  beforeEach(() => {
    service = new FakeMailSettingsService();
    component = new MailComponent(service as unknown as MailSettingsService);
    component.ngOnInit();
  });

  it('omits password when the password field is left empty', () => {
    component.form.patchValue({ host: 'smtp.new.test', password: '' });

    component.save();

    expect(service.updateCalls.length).toBe(1);
    expect(service.updateCalls[0].host).toBe('smtp.new.test');
    expect(service.updateCalls[0].password).toBeUndefined();
  });

  it('sends the password when the field has a value', () => {
    component.form.patchValue({ password: 'new-secret' });

    component.save();

    expect(service.updateCalls.length).toBe(1);
    expect(service.updateCalls[0].password).toBe('new-secret');
  });

  it('sends a test mail with the recipient and stores the result', () => {
    service.testResult = { success: false, category: 'CONFIG_MISSING', message: 'aus' };
    component.testRecipient.setValue('parent@example.test');

    component.sendTest();

    expect(service.testCalls).toEqual(['parent@example.test']);
    expect(component.testResult).toEqual({ success: false, category: 'CONFIG_MISSING', message: 'aus' });
  });
});
