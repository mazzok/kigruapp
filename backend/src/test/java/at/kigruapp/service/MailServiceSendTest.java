package at.kigruapp.service;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MailServiceSendTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Inject
    MailService mailService;

    @BeforeEach
    void setup() {
        MailSettings.deleteAll();
        MailSettings s = new MailSettings();
        s.host = "127.0.0.1";
        s.port = greenMail.getSmtp().getPort();
        s.encryption = MailEncryption.NONE;
        s.username = null;
        s.encryptedPassword = null;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();
    }

    @Test
    void sendDeliversMailToGreenMail() throws Exception {
        mailService.send("parent@example.test", "Hallo", "Testinhalt");

        assertTrue(greenMail.waitForIncomingEmail(5000, 1), "one mail must arrive");
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals("Hallo", received[0].getSubject());
        assertEquals("parent@example.test", received[0].getAllRecipients()[0].toString());
        assertTrue(received[0].getFrom()[0].toString().contains("kita@example.test"));
    }
}
