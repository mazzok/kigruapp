package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/**
 * Read view of the mail settings. Deliberately omits the password — the client
 * only learns whether one is stored via {@link #passwordSet}.
 */
public class MailSettingsDto {
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
    public boolean passwordSet;
}
