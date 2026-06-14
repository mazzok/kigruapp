package at.kigruapp.security;

import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
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

    CurrentUserService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CurrentUserService();
        service.mongoClient = mongoClient;
        service.databaseName = "testdb";
        service.oidcEnabled = false;

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

        // Pre-populate cache to bypass Panache static calls
        service.cachedPerson = person;
        // Mark resolved so getCurrentPerson() returns the cached value
        // We need to set resolved=true via reflection or restructure
        // Instead, test isAdmin() after manually setting cachedPerson and calling getCurrentPerson
        // The resolved flag is private — we trigger resolution by calling getCurrentPerson with oidcEnabled=false
        // But that would invoke Panache. So we set resolved via the fact that cachedPerson is set
        // and bypass by using the package-private access of the test.
        // Since resolved is private, we directly call isAdmin() with the cache pre-set:
        // getCurrentPerson() will be called inside isAdmin() — in dev mode it calls findFirstAdminOrFirstPerson()
        // which calls Person.listAll() (Panache). We cannot easily bypass this without resolved=true.
        // Solution: call getCurrentPerson() once to set resolved=true, but override cachedPerson after.
        // We expose cachedPerson as public so we can set it. resolved is private and set inside getCurrentPerson().
        // We use reflection to set resolved=true.
        try {
            var resolvedField = CurrentUserService.class.getDeclaredField("resolved");
            resolvedField.setAccessible(true);
            resolvedField.set(service, true);
        } catch (Exception e) {
            fail("Could not set resolved field: " + e.getMessage());
        }

        Document adminDoc = new Document("_id", roleInstanceId).append("value", "ADMIN");
        when(findIterable.first()).thenReturn(adminDoc);

        assertTrue(service.isAdmin());
    }

    @Test
    void isAdmin_returnsFalseWhenNoRoles() {
        Person person = new Person();
        person.roles = List.of();

        service.cachedPerson = person;
        try {
            var resolvedField = CurrentUserService.class.getDeclaredField("resolved");
            resolvedField.setAccessible(true);
            resolvedField.set(service, true);
        } catch (Exception e) {
            fail("Could not set resolved field: " + e.getMessage());
        }

        assertFalse(service.isAdmin());
        // No MongoDB calls should happen when roles is empty
        verifyNoInteractions(mongoClient);
    }

    @Test
    void isAdmin_returnsFalseWhenNullPerson() {
        service.cachedPerson = null;
        try {
            var resolvedField = CurrentUserService.class.getDeclaredField("resolved");
            resolvedField.setAccessible(true);
            resolvedField.set(service, true);
        } catch (Exception e) {
            fail("Could not set resolved field: " + e.getMessage());
        }

        assertFalse(service.isAdmin());
        verifyNoInteractions(mongoClient);
    }

    @Test
    void getCurrentPerson_returnsCachedValueWhenAlreadyResolved() {
        Person person = new Person();
        service.cachedPerson = person;
        try {
            var resolvedField = CurrentUserService.class.getDeclaredField("resolved");
            resolvedField.setAccessible(true);
            resolvedField.set(service, true);
        } catch (Exception e) {
            fail("Could not set resolved field: " + e.getMessage());
        }

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
        try {
            var resolvedField = CurrentUserService.class.getDeclaredField("resolved");
            resolvedField.setAccessible(true);
            resolvedField.set(service, true);
        } catch (Exception e) {
            fail("Could not set resolved field: " + e.getMessage());
        }

        when(findIterable.first()).thenReturn(null);

        assertFalse(service.isAdmin());
    }
}
