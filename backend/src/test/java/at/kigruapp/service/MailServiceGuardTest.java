package at.kigruapp.service;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailEncryption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit test (no CDI, no network) of the {@link MailService#sendHtml} account
 * guards. Builds a MailService with a stub EncryptionService and asserts the
 * CONFIG_MISSING guards fire before any SMTP connection is attempted.
 */
class MailServiceGuardTest {

    /** Stub whose configured-state is fixed at construction; never touches real config. */
    private static class StubEncryptionService extends EncryptionService {
        private final boolean configured;

        StubEncryptionService(boolean configured) {
            this.configured = configured;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }
    }

    private MailService serviceWith(boolean encryptionConfigured) {
        MailService svc = new MailService();
        svc.encryptionService = new StubEncryptionService(encryptionConfigured);
        return svc;
    }

    private MailAccount enabledAccount() {
        MailAccount a = new MailAccount();
        a.name = "Test";
        a.host = "127.0.0.1";
        a.port = 3025;
        a.encryption = MailEncryption.NONE;
        a.fromAddress = "kita@example.test";
        a.fromName = "Kita";
        a.enabled = true;
        return a;
    }

    @Test
    void encryptionNotConfigured_throwsConfigMissing() {
        MailService svc = serviceWith(false); // encryption key missing → fail-closed

        MailException ex = assertThrows(MailException.class,
                () -> svc.sendHtml(enabledAccount(), "a@b.test", "s", "<p>b</p>"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }

    @Test
    void disabledAccount_throwsConfigMissing() {
        MailService svc = serviceWith(true);
        MailAccount a = enabledAccount();
        a.enabled = false;

        MailException ex = assertThrows(MailException.class,
                () -> svc.sendHtml(a, "a@b.test", "s", "<p>b</p>"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }

    @Test
    void nullAccount_throwsConfigMissing() {
        MailService svc = serviceWith(true);

        MailException ex = assertThrows(MailException.class,
                () -> svc.sendHtml(null, "a@b.test", "s", "<p>b</p>"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }

    @Test
    void incompleteAccount_throwsConfigMissing() {
        MailService svc = serviceWith(true);
        MailAccount a = enabledAccount();
        a.host = null; // incomplete → CONFIG_MISSING

        MailException ex = assertThrows(MailException.class,
                () -> svc.sendHtml(a, "a@b.test", "s", "<p>b</p>"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }
}
