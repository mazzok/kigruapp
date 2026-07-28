import { MailComponent } from './mail.component';

describe('MailComponent', () => {
  it('constructs (tab host with no SMTP form or test-mail state)', () => {
    const component = new MailComponent();
    expect(component).toBeTruthy();
    // Regression guard: the removed test-mail API must not reappear here.
    expect((component as unknown as { sendTest?: unknown }).sendTest).toBeUndefined();
  });
});
