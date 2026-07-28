package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.Instant;

@MongoEntity(collection = "hourEntries")
public class HourEntry extends PanacheMongoEntity {
    public ObjectId personId;
    public ObjectId semesterId;
    /** null bedeutet die fixe Tätigkeit "Kochen". */
    public ObjectId roleFieldInstanceId;
    public ObjectId roleDefinitionId;
    /** Snapshot des Rollen-Labels zum Erfassungszeitpunkt; "Kochen" für den Koch-Fall. */
    public String roleLabel;
    /** Tätigkeitsdatum als YYYY-MM-DD. */
    public String date;
    public int minutes;
    public String comment;
    public Instant createdAt;
    public Instant updatedAt;
}
