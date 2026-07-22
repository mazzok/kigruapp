package at.kigruapp.service;

/**
 * Thrown by {@link MailService} when a mail cannot be sent. The {@link #category}
 * is a normalized, client-safe classification (no raw SMTP text leaks through it).
 */
public class MailException extends RuntimeException {

    public enum Category {
        CONFIG_MISSING,
        AUTH_FAILED,
        CONNECTION_FAILED,
        UNKNOWN
    }

    public final Category category;

    public MailException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public MailException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }
}
