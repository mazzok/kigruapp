package at.kigruapp.entity;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class MailSettingsEntityTest {

    @BeforeEach
    void cleanup() {
        MailSettings.deleteAll();
    }

    @Test
    void upsertKeepsSingleAndOverwrites() {
        MailSettings first = new MailSettings();
        first.host = "smtp.one.example";
        first.port = 25;
        first.encryption = MailEncryption.NONE;
        first.enabled = false;
        first.persistSingleton();

        MailSettings second = new MailSettings();
        second.host = "smtp.two.example";
        second.port = 587;
        second.encryption = MailEncryption.STARTTLS;
        second.enabled = true;
        second.persistSingleton();

        assertEquals(1, MailSettings.count(), "there must be exactly one mail_settings document");
        MailSettings found = MailSettings.findSingleton();
        assertNotNull(found);
        assertEquals("smtp.two.example", found.host, "second upsert must overwrite the first");
        assertEquals(587, found.port);
        assertEquals(MailEncryption.STARTTLS, found.encryption);
    }
}
