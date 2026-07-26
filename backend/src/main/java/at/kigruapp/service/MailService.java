package at.kigruapp.service;

import at.kigruapp.entity.MailSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * Sends mail via jakarta.mail using the stored {@link MailSettings}. A fresh
 * {@code Session} is built per send from the runtime DB settings.
 */
@ApplicationScoped
public class MailService {

    @Inject
    EncryptionService encryptionService;

    /** Send a plaintext mail using the stored settings. */
    public void send(String recipient, String subject, String body) {
        try {
            MimeMessage msg = prepareMessage(recipient, subject);
            msg.setText(body, "UTF-8");
            Transport.send(msg);
        } catch (AuthenticationFailedException e) {
            throw new MailException(MailException.Category.AUTH_FAILED,
                    "Authentifizierung am Mailserver fehlgeschlagen", e);
        } catch (MessagingException e) {
            throw new MailException(MailException.Category.CONNECTION_FAILED,
                    "Verbindung zum Mailserver fehlgeschlagen", e);
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            throw new MailException(MailException.Category.UNKNOWN,
                    "Unbekannter Fehler beim Mailversand", e);
        }
    }

    /** Send an HTML mail using the stored settings. */
    public void sendHtml(String recipient, String subject, String htmlBody) {
        try {
            MimeMessage msg = prepareMessage(recipient, subject);
            msg.setContent(htmlBody, "text/html; charset=UTF-8");
            Transport.send(msg);
        } catch (AuthenticationFailedException e) {
            throw new MailException(MailException.Category.AUTH_FAILED,
                    "Authentifizierung am Mailserver fehlgeschlagen", e);
        } catch (MessagingException e) {
            throw new MailException(MailException.Category.CONNECTION_FAILED,
                    "Verbindung zum Mailserver fehlgeschlagen", e);
        } catch (MailException e) {
            throw e;
        } catch (Exception e) {
            throw new MailException(MailException.Category.UNKNOWN,
                    "Unbekannter Fehler beim Mailversand", e);
        }
    }

    /**
     * Runs the guard checks, builds the session, and returns a MimeMessage with
     * from/to/subject already set. Shared by {@link #send} and {@link #sendHtml}.
     */
    private MimeMessage prepareMessage(String recipient, String subject)
            throws MessagingException, java.io.UnsupportedEncodingException {
        if (!encryptionService.isConfigured()) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Verschlüsselung ist nicht konfiguriert");
        }
        MailSettings s = MailSettings.findSingleton();
        if (s == null || !s.enabled) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mailversand ist deaktiviert");
        }
        if (isIncomplete(s)) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mail-Einstellungen sind unvollständig");
        }
        Properties props = buildProperties(s);
        String password = (s.encryptedPassword != null && !s.encryptedPassword.isBlank())
                ? encryptionService.decrypt(s.encryptedPassword)
                : null;

        Session session;
        if (s.username != null && !s.username.isBlank() && password != null) {
            final String user = s.username;
            final String pw = password;
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pw);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        MimeMessage msg = new MimeMessage(session);
        if (s.fromName != null && !s.fromName.isBlank()) {
            msg.setFrom(new InternetAddress(s.fromAddress, s.fromName));
        } else {
            msg.setFrom(new InternetAddress(s.fromAddress));
        }
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
        msg.setSubject(subject);
        return msg;
    }

    private boolean isIncomplete(MailSettings s) {
        if (s.host == null || s.host.isBlank()) {
            return true;
        }
        if (s.port < 1 || s.port > 65535) {
            return true;
        }
        if (s.encryption == null) {
            return true;
        }
        if (s.fromAddress == null || s.fromAddress.isBlank()) {
            return true;
        }
        // username set but no stored password → incomplete auth config
        return s.username != null && !s.username.isBlank()
                && (s.encryptedPassword == null || s.encryptedPassword.isBlank());
    }

    /** Build jakarta.mail SMTP properties from settings (timeouts + transport hardening). */
    Properties buildProperties(MailSettings s) {
        Properties p = new Properties();
        p.put("mail.smtp.host", s.host);
        p.put("mail.smtp.port", String.valueOf(s.port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        if (s.username != null && !s.username.isBlank()) {
            p.put("mail.smtp.auth", "true");
        }
        switch (s.encryption) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case SSL_TLS -> p.put("mail.smtp.ssl.enable", "true");
            case NONE -> { /* no transport security */ }
        }
        return p;
    }
}
