package at.kigruapp.service;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class MailServiceGuardTest {

    @Inject
    MailService mailService;

    @BeforeEach
    void cleanup() {
        MailSettings.deleteAll();
    }

    private MailSettings baseSettings() {
        MailSettings s = new MailSettings();
        s.host = "127.0.0.1";
        s.port = 3025;
        s.encryption = MailEncryption.NONE;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        return s;
    }

    @Test
    void disabled_throwsConfigMissing() {
        MailSettings s = baseSettings();
        s.enabled = false;
        s.persistSingleton();

        MailException ex = assertThrows(MailException.class,
                () -> mailService.send("a@b.test", "s", "b"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }

    @Test
    void missingKey_throwsConfigMissing() {
        MailSettings s = baseSettings();
        s.enabled = true;
        s.username = "mailer";
        s.encryptedPassword = "some-blob";
        s.persistSingleton();

        // MailService whose EncryptionService has no key configured
        MailService svc = new MailService();
        EncryptionService noKey = new EncryptionService();
        noKey.encryptionKey = Optional.empty();
        svc.encryptionService = noKey;

        MailException ex = assertThrows(MailException.class,
                () -> svc.send("a@b.test", "s", "b"));
        assertEquals(MailException.Category.CONFIG_MISSING, ex.category);
    }
}
