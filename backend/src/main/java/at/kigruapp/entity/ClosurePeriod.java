package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.types.ObjectId;

import java.time.LocalDate;
import java.util.List;

/**
 * Ein zusammenhaengender Schliesszeitraum, beide Grenzen inklusive.
 *
 * <p>LocalDate statt Instant: Schliesstage sind Kalendertage ohne Uhrzeit,
 * Instant wuerde bei jedem Zonenwechsel die Tagesgrenzen verschieben.
 * Kein Semesterfeld — das Semester ist nur das Anzeigefenster.
 */
@MongoEntity(collection = "closure_periods")
public class ClosurePeriod extends PanacheMongoEntity {
    public LocalDate from;
    public LocalDate to;
    public ObjectId definitionId;

    public static List<ClosurePeriod> findByDefinition(ObjectId definitionId) {
        return list("definitionId", definitionId);
    }

    /** Alle Zeitraeume, die das Fenster [from, to] beruehren. */
    public static List<ClosurePeriod> findOverlapping(LocalDate from, LocalDate to) {
        return list("{'from': {'$lte': ?1}, 'to': {'$gte': ?2}}", to, from);
    }
}
