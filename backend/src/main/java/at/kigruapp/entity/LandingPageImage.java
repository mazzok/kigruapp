package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.time.Instant;

/**
 * Ein in die Startseite eingebettetes Bild. Wird separat von {@link LandingPage}
 * gespeichert, damit der Sanitizer im bodyHtml nur eine http(s)-URL sieht statt
 * einer Base64-{@code data:}-URI (die er sonst herausfiltert, siehe
 * {@code LandingPageResource.WEB_HTML_POLICY}).
 */
@MongoEntity(collection = "landing_page_images")
public class LandingPageImage extends PanacheMongoEntity {
    public String contentType;
    public byte[] data;
    public Instant createdAt;
}
