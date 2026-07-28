package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/** Write payload. {@code password} null/blank keeps the stored one; {@code clearPassword} removes it. */
public class MailAccountUpdateDto {
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String password;
    public Boolean clearPassword;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
}
