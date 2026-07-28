package at.kigruapp.service;

import at.kigruapp.entity.MailAccount;
import at.kigruapp.entity.MailEncryption;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Plain unit test of the SMTP properties builder (no CDI, no network). */
class MailServicePropertiesTest {

    private MailAccount settings(MailEncryption enc, String username) {
        MailAccount s = new MailAccount();
        s.host = "smtp.example.test";
        s.port = 587;
        s.encryption = enc;
        s.username = username;
        s.fromAddress = "kita@example.test";
        return s;
    }

    @Test
    void timeoutsAndIdentityCheckAlwaysSet() {
        Properties p = new MailService().buildProperties(settings(MailEncryption.NONE, null));
        assertEquals("10000", p.getProperty("mail.smtp.connectiontimeout"));
        assertEquals("10000", p.getProperty("mail.smtp.timeout"));
        assertEquals("true", p.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("smtp.example.test", p.getProperty("mail.smtp.host"));
        assertEquals("587", p.getProperty("mail.smtp.port"));
    }

    @Test
    void starttlsModeRequiresStarttls() {
        Properties p = new MailService().buildProperties(settings(MailEncryption.STARTTLS, null));
        assertEquals("true", p.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", p.getProperty("mail.smtp.starttls.required"));
    }

    @Test
    void sslModeEnablesSsl() {
        Properties p = new MailService().buildProperties(settings(MailEncryption.SSL_TLS, null));
        assertEquals("true", p.getProperty("mail.smtp.ssl.enable"));
    }

    @Test
    void authOnlyWhenUsernamePresent() {
        Properties withUser = new MailService().buildProperties(settings(MailEncryption.NONE, "mailer"));
        assertEquals("true", withUser.getProperty("mail.smtp.auth"));
        Properties noUser = new MailService().buildProperties(settings(MailEncryption.NONE, null));
        assertNull(noUser.getProperty("mail.smtp.auth"));
    }

    @Test
    void noneModeHasNoStarttls() {
        Properties p = new MailService().buildProperties(settings(MailEncryption.NONE, null));
        assertNull(p.getProperty("mail.smtp.starttls.required"));
        assertFalse(p.containsKey("mail.smtp.ssl.enable"));
    }
}
