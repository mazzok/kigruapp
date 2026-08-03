package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Genau ein Dokument: welche Attribute die Eltern-Uebersicht zeigt. Gilt global,
 * unabhaengig von Gruppe und Semester.
 */
@MongoEntity(collection = "parent_directory_settings")
public class ParentDirectorySettings extends PanacheMongoEntity {
    public List<String> visibleAttributes = new ArrayList<>();

    public static ParentDirectorySettings findSingleton() {
        return findAll().firstResult();
    }
}
