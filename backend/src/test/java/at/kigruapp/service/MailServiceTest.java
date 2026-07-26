package at.kigruapp.service;

import at.kigruapp.entity.MailEncryption;
import at.kigruapp.entity.MailSettings;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
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
class MailServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Inject
    MailService mailService;

    @BeforeEach
    void setup() {
        MailSettings.deleteAll();
        MailSettings s = new MailSettings();
        s.host = "localhost";
        s.port = ServerSetupTest.SMTP.getPort();
        s.encryption = MailEncryption.NONE;
        s.fromAddress = "kita@example.test";
        s.fromName = "Kita";
        s.enabled = true;
        s.persistSingleton();
    }

    @Test
    void sendHtmlDeliversHtmlContentType() throws Exception {
        mailService.sendHtml("parent@example.test", "Willkommen", "<p>Hallo <b>Peter</b></p>");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        MimeMessage msg = received[0];
        assertTrue(msg.getContentType().toLowerCase().contains("text/html"));
        String body = GreenMailUtil.getBody(msg);
        assertTrue(body.contains("<b>Peter</b>"));
    }

    @Test
    void sendDeliversPlainText() throws Exception {
        mailService.send("parent@example.test", "Willkommen", "Hallo Peter");

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        MimeMessage msg = received[0];
        assertTrue(msg.getContentType().toLowerCase().contains("text/plain"));
    }
}
