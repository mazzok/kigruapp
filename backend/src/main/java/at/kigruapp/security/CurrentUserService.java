package at.kigruapp.security;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RequestScoped
public class CurrentUserService {

    @Inject
    public MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    public String databaseName;

    @Inject
    public SecurityIdentity identity;

    @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "true")
    public boolean oidcEnabled;

    Person cachedPerson;
    boolean resolved = false;

    public Person getCurrentPerson() {
        if (resolved) return cachedPerson;
        resolved = true;

        if (!oidcEnabled) {
            cachedPerson = findFirstAdminOrFirstPerson();
            return cachedPerson;
        }

        if (identity == null || identity.isAnonymous()) {
            cachedPerson = null;
            return null;
        }

        if (!(identity.getPrincipal() instanceof JsonWebToken jwt)) {
            return null;
        }
        String sub = jwt.getSubject();
        String email = jwt.getClaim("email");

        Person person = Person.find("keycloakUserId", sub).firstResult();

        if (person == null && email != null) {
            person = findPersonByEmail(email);
            if (person != null) {
                person.keycloakUserId = sub;
                person.update();
            }
        }

        cachedPerson = person;
        return cachedPerson;
    }

    public ObjectId getCurrentFamilyId() {
        Person p = getCurrentPerson();
        return p != null ? p.familyId : null;
    }

    public boolean isAdmin() {
        Person p = getCurrentPerson();
        if (p == null || p.roles == null || p.roles.isEmpty()) return false;

        List<ObjectId> roleInstanceIds = p.roles.stream()
            .map(ref -> ref.fieldInstanceId)
            .collect(Collectors.toList());

        MongoCollection<Document> col = mongoClient
            .getDatabase(databaseName)
            .getCollection("field_instances");

        Document adminDoc = col.find(
            Filters.and(
                Filters.in("_id", roleInstanceIds),
                Filters.eq("value", "ADMIN")
            )
        ).first();

        return adminDoc != null;
    }

    private Person findFirstAdminOrFirstPerson() {
        List<Person> all = Person.listAll();
        for (Person p : all) {
            if (p.roles != null && !p.roles.isEmpty()) {
                List<ObjectId> ids = p.roles.stream()
                    .map(r -> r.fieldInstanceId)
                    .collect(Collectors.toList());
                MongoCollection<Document> col = mongoClient
                    .getDatabase(databaseName)
                    .getCollection("field_instances");
                Document doc = col.find(
                    Filters.and(Filters.in("_id", ids), Filters.eq("value", "ADMIN"))
                ).first();
                if (doc != null) return p;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private Person findPersonByEmail(String email) {
        MongoCollection<Document> col = mongoClient
            .getDatabase(databaseName)
            .getCollection("field_instances");
        List<Document> emailDocs = col.find(Filters.eq("value", email))
            .into(new ArrayList<>());
        if (emailDocs.isEmpty()) return null;

        List<ObjectId> instanceIds = emailDocs.stream()
            .map(d -> d.getObjectId("_id"))
            .collect(Collectors.toList());

        List<Person> persons = Person.listAll();
        for (Person p : persons) {
            if (p.basicProperties == null) continue;
            for (FieldRef ref : p.basicProperties) {
                if (instanceIds.contains(ref.fieldInstanceId)) return p;
            }
        }
        return null;
    }
}
