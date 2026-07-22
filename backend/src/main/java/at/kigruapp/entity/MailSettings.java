package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

/**
 * Singleton document holding the application's SMTP configuration.
 * Identity is pinned to a constant {@link #SINGLETON_ID} so concurrent upserts
 * can never create a second document.
 */
@MongoEntity(collection = "mail_settings")
public class MailSettings extends PanacheMongoEntity {

    /** Fixed well-known id — there is only ever one mail_settings document. */
    public static final ObjectId SINGLETON_ID = new ObjectId("000000000000000000000001");

    public String host;
    public int port;
    public MailEncryption encryption;
    public String username;
    public String encryptedPassword;
    public String fromAddress;
    public String fromName;
    public boolean enabled;

    /** @return the singleton document, or null if none has been saved yet. */
    public static MailSettings findSingleton() {
        return findById(SINGLETON_ID);
    }

    /** Persist or update this instance under the fixed singleton id (upsert). */
    public void persistSingleton() {
        this.id = SINGLETON_ID;
        persistOrUpdate();
    }
}
