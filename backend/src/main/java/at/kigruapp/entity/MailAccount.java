package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

/** One SMTP sender account. Ordinary id — there is no singleton. */
@MongoEntity(collection = "mail_accounts")
public class MailAccount extends PanacheMongoEntity {
    public String name;
    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String encryptedPassword;
    public String fromAddress;
    public String fromName;
    public boolean enabled;
}
