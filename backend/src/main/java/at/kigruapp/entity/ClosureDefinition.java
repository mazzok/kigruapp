package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Art eines Schliesstags, z.B. "Weihnachtsferien". Generell und immer gueltig,
 * ohne Datums- oder Semesterbezug.
 */
@MongoEntity(collection = "closure_definitions")
public class ClosureDefinition extends PanacheMongoEntity {
    public String label;
    public String color;
    public boolean active;
    public Instant createdAt;
}
