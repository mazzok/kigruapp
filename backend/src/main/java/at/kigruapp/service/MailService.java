package at.kigruapp.service;

import at.kigruapp.entity.MailAccount;
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
 * Sends mail via jakarta.mail using the account's SMTP config. A fresh
 * {@code Session} is built per send from the given {@link MailAccount}.
 */
@ApplicationScoped
public class MailService {

    @Inject
    EncryptionService encryptionService;

    /** Send an HTML mail using the given account's SMTP config. */
    public void sendHtml(MailAccount account, String recipient, String subject, String htmlBody) {
        try {
            MimeMessage msg = prepareMessage(account, recipient, subject);
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

    private MimeMessage prepareMessage(MailAccount account, String recipient, String subject)
            throws MessagingException, java.io.UnsupportedEncodingException {
        if (!encryptionService.isConfigured()) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Verschlüsselung ist nicht konfiguriert");
        }
        if (account == null || !account.enabled) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mailversand ist deaktiviert");
        }
        if (isIncomplete(account)) {
            throw new MailException(MailException.Category.CONFIG_MISSING,
                    "Mail-Einstellungen sind unvollständig");
        }
        Properties props = buildProperties(account);
        String password = (account.encryptedPassword != null && !account.encryptedPassword.isBlank())
                ? encryptionService.decrypt(account.encryptedPassword)
                : null;

        Session session;
        if (account.username != null && !account.username.isBlank() && password != null) {
            final String user = account.username;
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
        if (account.fromName != null && !account.fromName.isBlank()) {
            msg.setFrom(new InternetAddress(account.fromAddress, account.fromName));
        } else {
            msg.setFrom(new InternetAddress(account.fromAddress));
        }
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
        msg.setSubject(subject);
        return msg;
    }

    private boolean isIncomplete(MailAccount a) {
        if (a.host == null || a.host.isBlank()) {
            return true;
        }
        if (a.port < 1 || a.port > 65535) {
            return true;
        }
        if (a.encryption == null) {
            return true;
        }
        if (a.fromAddress == null || a.fromAddress.isBlank()) {
            return true;
        }
        // username set but no stored password → incomplete auth config
        return a.username != null && !a.username.isBlank()
                && (a.encryptedPassword == null || a.encryptedPassword.isBlank());
    }

    /** Build jakarta.mail SMTP properties from the account (timeouts + transport hardening). */
    Properties buildProperties(MailAccount a) {
        Properties p = new Properties();
        p.put("mail.smtp.host", a.host);
        p.put("mail.smtp.port", String.valueOf(a.port));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        if (a.username != null && !a.username.isBlank()) {
            p.put("mail.smtp.auth", "true");
        }
        switch (a.encryption) {
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
