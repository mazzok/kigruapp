package at.kigruapp.resource;

import at.kigruapp.entity.Family;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/setup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @GET
    @Path("/status")
    public Response status() {
        boolean required = Person.count() == 0;
        return Response.ok(new Document("required", required).toJson()).build();
    }

    @POST
    public Response setup(SetupRequest request) {
        if (Person.count() > 0) {
            return Response.status(403).entity("{\"error\": \"Setup already completed\"}").build();
        }
        if (request == null || request.familyName == null || request.familyName.isBlank()) {
            return Response.status(400).entity("{\"error\": \"familyName required\"}").build();
        }
        if (request.keycloakUserId == null || request.keycloakUserId.isBlank() ||
            request.email == null || request.email.isBlank()) {
            return Response.status(400).entity("{\"error\": \"keycloakUserId and email required\"}").build();
        }

        MongoCollection<Document> fieldInstancesCol = mongoClient
            .getDatabase(databaseName)
            .getCollection("field_instances");

        // 1. Create Family
        Family family = new Family();
        family.name = request.familyName;
        family.createdAt = Instant.now();
        family.persist();

        // 2. Create email FieldInstance
        ObjectId emailDefId = findFieldDefinitionId("email");
        Document emailInstance = new Document()
            .append("definitionId", emailDefId)
            .append("value", request.email)
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(emailInstance);
        ObjectId emailInstanceId = emailInstance.getObjectId("_id");

        // 2b. Create firstName FieldInstance
        ObjectId firstNameDefId = findFieldDefinitionId("firstName");
        Document firstNameInstance = new Document()
            .append("definitionId", firstNameDefId)
            .append("value", request.firstName != null ? request.firstName : "")
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(firstNameInstance);
        ObjectId firstNameInstanceId = firstNameInstance.getObjectId("_id");

        // 2c. Create lastName FieldInstance
        ObjectId lastNameDefId = findFieldDefinitionId("lastName");
        Document lastNameInstance = new Document()
            .append("definitionId", lastNameDefId)
            .append("value", request.lastName != null ? request.lastName : "")
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(lastNameInstance);
        ObjectId lastNameInstanceId = lastNameInstance.getObjectId("_id");

        // 3. Create ADMIN role FieldInstance
        ObjectId roleDefId = findFieldDefinitionId("role");
        Document roleInstance = new Document()
            .append("definitionId", roleDefId)
            .append("value", "ADMIN")
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(roleInstance);
        ObjectId roleInstanceId = roleInstance.getObjectId("_id");

        // 3b. Create personType PARENT FieldInstance
        ObjectId personTypeDefId = findFieldDefinitionId("personType");
        Document personTypeInstance = new Document()
            .append("definitionId", personTypeDefId)
            .append("value", "PARENT")
            .append("createdAt", Instant.now())
            .append("updatedAt", Instant.now());
        fieldInstancesCol.insertOne(personTypeInstance);
        ObjectId personTypeInstanceId = personTypeInstance.getObjectId("_id");

        // 4. Create Person
        Person person = new Person();
        person.familyId = (ObjectId) family.id;
        person.keycloakUserId = request.keycloakUserId;
        person.basicProperties = new ArrayList<>(List.of(
            new FieldRef(firstNameDefId, firstNameInstanceId),
            new FieldRef(lastNameDefId, lastNameInstanceId),
            new FieldRef(emailDefId, emailInstanceId),
            new FieldRef(personTypeDefId, personTypeInstanceId)
        ));
        person.roles = new ArrayList<>(List.of(new FieldRef(roleDefId, roleInstanceId)));
        person.schedules = new ArrayList<>();
        person.duties = new ArrayList<>();
        person.finance = new ArrayList<>();
        person.customProperties = new ArrayList<>();
        person.createdAt = Instant.now();
        person.updatedAt = Instant.now();
        person.persist();

        return Response.status(201)
            .entity("{\"personId\": \"" + person.id + "\", \"familyId\": \"" + family.id + "\"}")
            .build();
    }

    private ObjectId findFieldDefinitionId(String fieldName) {
        Document def = mongoClient.getDatabase(databaseName)
            .getCollection("field_definitions")
            .find(new Document("fieldName", fieldName))
            .first();
        if (def == null) {
            throw new IllegalStateException("FieldDefinition '" + fieldName + "' not found. Run migrations first.");
        }
        return def.getObjectId("_id");
    }

    public static class SetupRequest {
        public String familyName;
        public String keycloakUserId;
        public String email;
        public String firstName;
        public String lastName;
    }
}
