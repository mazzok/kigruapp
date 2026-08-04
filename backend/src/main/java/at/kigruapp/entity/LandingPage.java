package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Inhalt der Startseite. Bewusst ein Singleton: es gibt genau eine Startseite,
 * die erste Zeile der Collection ist maßgeblich.
 */
@MongoEntity(collection = "landing_page")
public class LandingPage extends PanacheMongoEntity {
    public String bodyHtml;
    public Instant updatedAt;

    public static LandingPage findSingleton() {
        return findAll().firstResult();
    }
}
