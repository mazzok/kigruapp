package at.kigruapp.security;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.quarkus.security.identity.SecurityIdentity;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CurrentUserServiceTest {

    @Mock
    MongoClient mongoClient;

    @Mock
    MongoDatabase mongoDatabase;

    @Mock
    MongoCollection<Document> mongoCollection;

    @Mock
    FindIterable<Document> findIterable;

    @Mock
    SecurityIdentity identity;

    CurrentUserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CurrentUserService();
        service.mongoClient = mongoClient;
        service.databaseName = "testdb";
        service.oidcEnabled = false;
        service.identity = identity;
        service.resolved = false;
        service.cachedPerson = null;

        when(mongoClient.getDatabase("testdb")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("fieldInstances")).thenReturn(mongoCollection);
        when(mongoCollection.find(any(org.bson.conversions.Bson.class))).thenReturn(findIterable);
    }

    @Test
    void isAdmin_returnsTrueWhenAdminRoleExists() {
        ObjectId roleInstanceId = new ObjectId();
        FieldRef roleRef = new FieldRef(new ObjectId(), roleInstanceId);

        Person person = new Person();
        person.roles = List.of(roleRef);

        service.cachedPerson = person;
        service.resolved = true;

        Document adminDoc = new Document("_id", roleInstanceId).append("value", "ADMIN");
        when(findIterable.first()).thenReturn(adminDoc);

        assertTrue(service.isAdmin());
    }

    @Test
    void isAdmin_returnsFalseWhenNoRoles() {
        Person person = new Person();
        person.roles = List.of();

        service.cachedPerson = person;
        service.resolved = true;

        assertFalse(service.isAdmin());
        // No MongoDB calls should happen when roles is empty
        verifyNoInteractions(mongoClient);
    }

    @Test
    void isAdmin_returnsFalseWhenNullPerson() {
        service.cachedPerson = null;
        service.resolved = true;

        assertFalse(service.isAdmin());
        verifyNoInteractions(mongoClient);
    }

    @Test
    void getCurrentPerson_returnsCachedValueWhenAlreadyResolved() {
        Person person = new Person();
        service.cachedPerson = person;
        service.resolved = true;

        Person result = service.getCurrentPerson();

        assertSame(person, result);
        // No DB interactions since already resolved
        verifyNoInteractions(mongoClient);
    }

    @Test
    void isAdmin_returnsFalseWhenAdminDocNotFound() {
        ObjectId roleInstanceId = new ObjectId();
        FieldRef roleRef = new FieldRef(new ObjectId(), roleInstanceId);

        Person person = new Person();
        person.roles = List.of(roleRef);

        service.cachedPerson = person;
        service.resolved = true;

        when(findIterable.first()).thenReturn(null);

        assertFalse(service.isAdmin());
    }

    @Test
    void getCurrentPerson_returnsNull_whenOidcEnabledAndAnonymous() {
        service.oidcEnabled = true;
        when(identity.isAnonymous()).thenReturn(true);
        assertNull(service.getCurrentPerson());
    }

    @Test
    void getCurrentPerson_returnsNull_whenPrincipalIsNotJwt() {
        service.oidcEnabled = true;
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(mock(java.security.Principal.class));
        assertNull(service.getCurrentPerson());
    }
}
