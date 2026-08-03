package at.kigruapp.service;

import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.ParentDirectorySettings;
import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Baut den Katalog waehlbarer Attribute der Eltern-Uebersicht und haelt die
 * global gueltige Auswahl. Der Katalog besteht aus einer festen Kernliste und
 * den benutzerdefinierten Feldern, die an mindestens einer Person gepflegt sind
 * — FieldDefinition kennt keine Entitaets-Zuordnung, aus der man das sonst
 * ableiten koennte.
 */
@ApplicationScoped
public class ParentDirectoryAttributeService {

    public static final String CHILD_NAME = "childName";
    public static final String CHILD_ENTRY_DATE = "childEntryDate";
    public static final String CHILD_EXIT_DATE = "childExitDate";
    public static final String FIRST_NAME = "firstName";
    public static final String LAST_NAME = "lastName";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String TEAM = "team";
    public static final String ROLE = "role";
    public static final String ADDRESS = "address";
    public static final String CUSTOM_PREFIX = "custom:";

    public static final String SCOPE_CHILD = "CHILD";
    public static final String SCOPE_PARENT = "PARENT";
    public static final String SCOPE_FAMILY = "FAMILY";

    private static final List<CatalogEntry> CORE = List.of(
            new CatalogEntry(CHILD_NAME, "Vorname", SCOPE_CHILD),
            new CatalogEntry(CHILD_ENTRY_DATE, "Eintritt", SCOPE_CHILD),
            new CatalogEntry(CHILD_EXIT_DATE, "Austritt", SCOPE_CHILD),
            new CatalogEntry(FIRST_NAME, "Vorname", SCOPE_PARENT),
            new CatalogEntry(LAST_NAME, "Nachname", SCOPE_PARENT),
            new CatalogEntry(EMAIL, "E-Mail", SCOPE_PARENT),
            new CatalogEntry(PHONE, "Telefon", SCOPE_PARENT),
            new CatalogEntry(TEAM, "Team", SCOPE_PARENT),
            new CatalogEntry(ROLE, "Rolle", SCOPE_PARENT),
            new CatalogEntry(ADDRESS, "Adresse", SCOPE_FAMILY));

    private static final Set<String> DEFAULTS = Set.of(
            CHILD_NAME, FIRST_NAME, LAST_NAME, EMAIL, PHONE, ADDRESS);

    public record CatalogEntry(String key, String label, String scope) {}

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    public List<CatalogEntry> catalog() {
        List<CatalogEntry> entries = new ArrayList<>(CORE);
        for (ObjectId definitionId : customDefinitionIds()) {
            FieldDefinition def = FieldDefinition.findById(definitionId);
            if (def == null) continue;
            String label = def.label != null ? def.label.get("de") : null;
            if (label == null || label.isBlank()) label = def.fieldName;
            entries.add(new CatalogEntry(CUSTOM_PREFIX + definitionId.toHexString(), label, SCOPE_PARENT));
        }
        return entries;
    }

    /** definitionIds, die an mindestens einer Person unter customProperties haengen. */
    public Set<ObjectId> customDefinitionIds() {
        Set<ObjectId> ids = new LinkedHashSet<>();
        mongoClient.getDatabase(databaseName).getCollection("persons")
                .distinct("customProperties.definitionId", ObjectId.class)
                .forEach(ids::add);
        return ids;
    }

    public Set<String> visibleKeys() {
        ParentDirectorySettings settings = ParentDirectorySettings.findSingleton();
        if (settings == null || settings.visibleAttributes == null || settings.visibleAttributes.isEmpty()) {
            return DEFAULTS;
        }
        Set<String> keys = new LinkedHashSet<>(settings.visibleAttributes);
        keys.add(CHILD_NAME);
        return keys;
    }

    public List<CatalogEntry> visibleCatalog() {
        Set<String> visible = visibleKeys();
        return catalog().stream().filter(e -> visible.contains(e.key())).collect(Collectors.toList());
    }

    public void save(List<String> keys) {
        Set<String> known = catalog().stream().map(CatalogEntry::key).collect(Collectors.toSet());
        List<String> unknown = (keys == null ? List.<String>of() : keys).stream()
                .filter(k -> !known.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Attribute: " + String.join(", ", unknown));
        }

        Set<String> selection = new LinkedHashSet<>(keys == null ? List.of() : keys);
        selection.add(CHILD_NAME);

        ParentDirectorySettings settings = ParentDirectorySettings.findSingleton();
        if (settings == null) {
            settings = new ParentDirectorySettings();
            settings.visibleAttributes = new ArrayList<>(selection);
            settings.persist();
        } else {
            settings.visibleAttributes = new ArrayList<>(selection);
            settings.update();
        }
    }

    /** Hilfsmittel fuer den Aufrufer: Map Label je Katalog-Schluessel. */
    public Map<String, String> labelsByKey() {
        return catalog().stream().collect(Collectors.toMap(CatalogEntry::key, CatalogEntry::label, (a, b) -> a));
    }
}
