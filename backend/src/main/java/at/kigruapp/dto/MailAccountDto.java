package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/** Read view of a mail account. Omits the password; {@link #passwordSet} tells whether one is stored. */
public class MailAccountDto {
    public String id;
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
    public boolean passwordSet;
}
