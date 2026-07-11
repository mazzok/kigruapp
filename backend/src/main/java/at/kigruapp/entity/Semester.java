package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

@MongoEntity(collection = "semesters")
public class Semester extends PanacheMongoEntity {
    public Instant start;
    public Instant end;
    public Instant createdAt;
}
