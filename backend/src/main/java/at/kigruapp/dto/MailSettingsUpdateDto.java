package at.kigruapp.dto;

import at.kigruapp.entity.MailEncryption;

/**
 * Write payload for the mail settings. {@code password} is optional: when null/blank
 * the stored password is kept; {@code clearPassword} removes it (see resource logic).
 */
public class MailSettingsUpdateDto {
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
