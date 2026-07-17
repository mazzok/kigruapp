package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.dto.PersonDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import at.kigruapp.entity.Semester;
import at.kigruapp.entity.SemesterAssignment;
import at.kigruapp.security.CurrentUserService;
import at.kigruapp.security.KeycloakUserService;
import at.kigruapp.service.JsonSchemaValidatorService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.panache.common.Sort;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Path("/api/v1/persons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    KeycloakUserService keycloakUserService;

    @Inject
    CurrentUserService currentUserService;

    @Inject
    JsonSchemaValidatorService schemaValidator;

    private MongoCollection<Document> getFieldInstancesCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    private MongoCollection<Document> getSemesterAssignmentsCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("semester_assignments");
    }

    private ObjectId resolveSemesterId(String semesterIdParam) {
        if (semesterIdParam != null && !semesterIdParam.isBlank()) {
            return new ObjectId(semesterIdParam);
        }
        List<Semester> latest = Semester.listAll(Sort.descending("createdAt"));
        if (latest.isEmpty()) {
            return null;
        }
        return latest.get(0).id;
    }

    private ObjectId requireSemesterId(String semesterIdParam) {
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        if (semesterId == null) {
            throw new BadRequestException("No semester available");
        }
        return semesterId;
    }

    private void toggleAssignment(ObjectId personId, ObjectId semesterId, String section, ObjectId defId, ObjectId instId) {
        MongoCollection<Document> assignments = getSemesterAssignmentsCollection();
        Document filter = new Document("personId", personId)
                .append("semesterId", semesterId)
                .append("section", section)
                .append("fieldInstanceId", instId);
        long count = assignments.countDocuments(filter);
        if (count > 0) {
            assignments.deleteMany(filter);
        } else {
            assignments.insertOne(new Document("_id", new ObjectId())
                    .append("personId", personId)
                    .append("semesterId", semesterId)
                    .append("section", section)
                    .append("definitionId", defId)
                    .append("fieldInstanceId", instId));
        }
    }

    private List<FieldInstanceDTO> resolveSemesterAssignments(ObjectId personId, ObjectId semesterId, String section) {
        if (semesterId == null) {
            return List.of();
        }
        Document filter = new Document("personId", personId)
                .append("semesterId", semesterId)
                .append("section", section);
        List<FieldRef> refs = new ArrayList<>();
        for (Document assignment : getSemesterAssignmentsCollection().find(filter)) {
            SemesterAssignment sa = SemesterAssignment.fromDocument(assignment);
            refs.add(new FieldRef(sa.definitionId, sa.fieldInstanceId));
        }
        return resolveRefs(refs);
    }

    @GET
    public List<Person> list(
            @QueryParam("familyId") String familyId,
            @QueryParam("personType") String personType) {
        if (familyId != null) {
            return Person.findByFamilyId(new ObjectId(familyId));
        }
        return Person.listAll();
    }

    @GET
    @Path("/me")
    public Response getMe(@QueryParam("semesterId") String semesterIdParam) {
        at.kigruapp.entity.Person currentPerson = currentUserService.getCurrentPerson();
        if (currentPerson == null) {
            return Response.status(403).build();
        }
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        PersonDTO dto = toFullDTO(currentPerson, semesterId);
        return Response.ok(dto).build();
    }

    @GET
    @Path("/{id}")
    public Person get(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        return person;
    }

    @GET
    @Path("/{id}/full")
    public PersonDTO getFull(@PathParam("id") String id, @QueryParam("semesterId") String semesterIdParam) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        return toFullDTO(person, semesterId);
    }

    public record ChildDTO(
        String id,
        String firstName,
        String lastName,
        String dateOfBirth,
        String groupDefinitionId,
        String groupInstanceId,
        String entryDate,
        String exitDate
    ) {}

    public record GroupAssignmentRequest(String definitionId, String fieldInstanceId) {}

    public record TeamAssignmentRequest(String definitionId, String fieldInstanceId) {}

    public record RoleAssignmentRequest(String definitionId, String fieldInstanceId) {}

    public record CreatePersonRequest(
        String familyId,
        List<SectionInput> basicProperties,
        List<SectionInput> roles,
        List<SectionInput> schedules,
        List<SectionInput> duties,
        List<SectionInput> finance,
        List<SectionInput> customProperties
    ) {}

    public record SectionInput(String definitionId, Object value) {}

    @POST
    public Response create(CreatePersonRequest request) {
        System.out.println("=== CREATE PERSON ===");
        System.out.println("familyId: " + request.familyId());
        System.out.println("basicProperties: " + (request.basicProperties() != null ? request.basicProperties().size() : "null"));
        if (request.basicProperties() != null) {
            for (SectionInput si : request.basicProperties()) {
                System.out.println("  def=" + si.definitionId() + " value=" + si.value() + " valueType=" + (si.value() != null ? si.value().getClass().getName() : "null"));
            }
        }
        Instant now = Instant.now();
        Person person = new Person();
        person.familyId = new ObjectId(request.familyId());
        person.createdAt = now;
        person.updatedAt = now;

        person.basicProperties = createFieldInstances(request.basicProperties(), now);
        person.roles = createFieldInstances(request.roles(), now);
        person.schedules = createFieldInstances(request.schedules(), now);
        person.duties = createFieldInstances(request.duties(), now);
        person.finance = createFieldInstances(request.finance(), now);
        person.customProperties = createFieldInstances(request.customProperties(), now);

        // Keycloak provisioning: find fields with keycloakMapping
        MongoCollection<Document> instColl = getFieldInstancesCollection();
        String email = null, firstName = null, lastName = null;
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && def.keycloakMapping != null) {
                Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (instDoc != null && instDoc.get("value") != null) {
                    switch (def.keycloakMapping) {
                        case "email" -> email = instDoc.get("value").toString();
                        case "firstName" -> firstName = instDoc.get("value").toString();
                        case "lastName" -> lastName = instDoc.get("value").toString();
                    }
                }
            }
        }

        if (email != null && !email.isBlank()) {
            try {
                person.keycloakUserId = keycloakUserService.createUser(email, firstName, lastName);
            } catch (Exception e) {
                System.err.println("Keycloak user creation failed: " + e.getMessage());
            }
        }

        person.persist();
        return Response.status(201).entity(person).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, CreatePersonRequest request) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        // Only replace sections that are explicitly provided (non-null) in the request
        Instant now = Instant.now();
        if (request.basicProperties() != null) {
            deleteFieldInstances(person.basicProperties);
            person.basicProperties = createFieldInstances(request.basicProperties(), now);
        }
        if (request.roles() != null) {
            deleteFieldInstances(person.roles);
            person.roles = createFieldInstances(request.roles(), now);
        }
        if (request.schedules() != null) {
            deleteFieldInstances(person.schedules);
            person.schedules = createFieldInstances(request.schedules(), now);
        }
        if (request.duties() != null) {
            deleteFieldInstances(person.duties);
            person.duties = createFieldInstances(request.duties(), now);
        }
        if (request.finance() != null) {
            deleteFieldInstances(person.finance);
            person.finance = createFieldInstances(request.finance(), now);
        }
        if (request.customProperties() != null) {
            deleteFieldInstances(person.customProperties);
            person.customProperties = createFieldInstances(request.customProperties(), now);
        }
        person.updatedAt = now;
        person.update();
        return Response.ok(person).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        deleteFieldInstances(person.basicProperties);
        deleteFieldInstances(person.roles);
        deleteFieldInstances(person.schedules);
        deleteFieldInstances(person.duties);
        deleteFieldInstances(person.finance);
        deleteFieldInstances(person.customProperties);
        getSemesterAssignmentsCollection().deleteMany(new Document("personId", person.id));
        person.delete();
        return Response.noContent().build();
    }

    private List<FieldRef> createFieldInstances(List<SectionInput> inputs, Instant now) {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        MongoCollection<Document> coll = getFieldInstancesCollection();
        Date nowDate = Date.from(now);
        List<FieldRef> refs = new ArrayList<>();
        for (SectionInput input : inputs) {
            if (isEmptyValue(input.value())) {
                continue;
            }
            ObjectId defId = new ObjectId(input.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                throw new BadRequestException("Definition not found: " + input.definitionId());
            }
            try {
                schemaValidator.validate(def.jsonSchema, input.value());
            } catch (JsonSchemaValidatorService.ValidationException e) {
                System.err.println("VALIDATION FAILED for " + def.fieldName + ": " + e.getMessage());
                System.err.println("  schema: " + def.jsonSchema);
                System.err.println("  value: " + input.value() + " (" + (input.value() != null ? input.value().getClass().getName() : "null") + ")");
                throw new BadRequestException(def.fieldName + ": " + e.getMessage());
            }
            ObjectId instId = new ObjectId();
            Document doc = new Document("_id", instId)
                    .append("definitionId", defId)
                    .append("value", input.value())
                    .append("createdAt", nowDate)
                    .append("updatedAt", nowDate);
            coll.insertOne(doc);
            refs.add(new FieldRef(defId, instId));
        }
        return refs;
    }

    private void deleteFieldInstances(List<FieldRef> refs) {
        if (refs == null) return;
        MongoCollection<Document> coll = getFieldInstancesCollection();
        for (FieldRef ref : refs) {
            coll.deleteOne(new Document("_id", ref.fieldInstanceId));
        }
    }

    private PersonDTO toFullDTO(Person person, ObjectId semesterId) {
        PersonDTO dto = new PersonDTO();
        dto.id = person.id.toHexString();
        dto.familyId = person.familyId.toHexString();
        dto.keycloakUserId = person.keycloakUserId;
        dto.basicProperties = resolveRefs(person.basicProperties);
        dto.roles = resolveRefs(person.roles);
        dto.schedules = resolveRefs(person.schedules);
        dto.duties = resolveRefs(person.duties);
        dto.finance = resolveRefs(person.finance);
        dto.customProperties = resolveRefs(person.customProperties);
        dto.assignedDuty = resolveSemesterAssignments(person.id, semesterId, "team");
        dto.assignedRole = resolveSemesterAssignments(person.id, semesterId, "role");
        dto.createdAt = person.createdAt != null ? person.createdAt.toString() : null;
        dto.updatedAt = person.updatedAt != null ? person.updatedAt.toString() : null;
        return dto;
    }

    private List<FieldInstanceDTO> resolveRefs(List<FieldRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        MongoCollection<Document> instColl = getFieldInstancesCollection();
        List<FieldInstanceDTO> dtos = new ArrayList<>();
        for (FieldRef ref : refs) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (def == null || instDoc == null) continue;

            FieldInstanceDTO dto = new FieldInstanceDTO();
            dto.id = instDoc.getObjectId("_id").toHexString();
            dto.definitionId = def.id.toHexString();
            dto.fieldName = def.fieldName;
            dto.label = def.label;
            dto.description = def.description;
            dto.jsonSchema = def.jsonSchema;
            dto.required = def.required;
            dto.keycloakMapping = def.keycloakMapping;
            dto.value = instDoc.get("value");
            dto.definitionOutdated = def.outdatedAt != null;
            dtos.add(dto);
        }
        return dtos;
    }

    @GET
    @Path("/children")
    public List<ChildDTO> listChildren(@QueryParam("semesterId") String semesterIdParam) {
        ObjectId semesterId = resolveSemesterId(semesterIdParam);
        List<Person> all = Person.listAll();
        List<ChildDTO> result = new ArrayList<>();
        for (Person person : all) {
            if (!isChild(person)) continue;
            result.add(toChildDTO(person, semesterId));
        }
        return result;
    }

    @PATCH
    @Path("/{id}/group")
    public Response patchGroup(
            @PathParam("id") String id,
            @QueryParam("semesterId") String semesterIdParam,
            GroupAssignmentRequest request) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) throw new NotFoundException();

        ObjectId semesterId = requireSemesterId(semesterIdParam);
        ObjectId defId = new ObjectId(request.definitionId());
        ObjectId instId = new ObjectId(request.fieldInstanceId());

        MongoCollection<Document> assignments = getSemesterAssignmentsCollection();
        Document filter = new Document("personId", person.id)
                .append("semesterId", semesterId)
                .append("section", "group");
        assignments.deleteMany(filter);
        assignments.insertOne(new Document("_id", new ObjectId())
                .append("personId", person.id)
                .append("semesterId", semesterId)
                .append("section", "group")
                .append("definitionId", defId)
                .append("fieldInstanceId", instId));

        return Response.noContent().build();
    }

    @PATCH
    @Path("/{id}/assigned-duty")
    public Response patchAssignedDuty(
            @PathParam("id") String id,
            @QueryParam("semesterId") String semesterIdParam,
            TeamAssignmentRequest request) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) throw new NotFoundException();

        ObjectId semesterId = requireSemesterId(semesterIdParam);
        ObjectId defId = new ObjectId(request.definitionId());
        ObjectId instId = new ObjectId(request.fieldInstanceId());

        toggleAssignment(person.id, semesterId, "team", defId, instId);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/{id}/assigned-role")
    public Response patchAssignedRole(
            @PathParam("id") String id,
            @QueryParam("semesterId") String semesterIdParam,
            RoleAssignmentRequest request) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) throw new NotFoundException();

        ObjectId semesterId = requireSemesterId(semesterIdParam);
        ObjectId defId = new ObjectId(request.definitionId());
        ObjectId instId = new ObjectId(request.fieldInstanceId());

        toggleAssignment(person.id, semesterId, "role", defId, instId);
        return Response.noContent().build();
    }

    private boolean isChild(Person person) {
        if (person.basicProperties == null) return false;
        MongoCollection<Document> instColl = getFieldInstancesCollection();
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && "personType".equals(def.fieldName)) {
                Document inst = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (inst != null && "CHILD".equals(inst.get("value"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private ChildDTO toChildDTO(Person person, ObjectId semesterId) {
        String firstName = resolveBasicValue(person, "firstName");
        String lastName = resolveBasicValue(person, "lastName");
        String dateOfBirth = resolveBasicValue(person, "dateOfBirth");

        String groupDefinitionId = null;
        String groupInstanceId = null;
        String entryDate = null;
        String exitDate = null;
        if (semesterId != null) {
            Document groupAssignment = getSemesterAssignmentsCollection()
                    .find(new Document("personId", person.id)
                            .append("semesterId", semesterId)
                            .append("section", "group"))
                    .first();
            if (groupAssignment != null) {
                groupDefinitionId = groupAssignment.getObjectId("definitionId").toHexString();
                groupInstanceId = groupAssignment.getObjectId("fieldInstanceId").toHexString();
                entryDate = groupAssignment.getString("entryDate");
                exitDate = groupAssignment.getString("exitDate");
            }
        }

        return new ChildDTO(
            person.id.toHexString(),
            firstName,
            lastName,
            dateOfBirth,
            groupDefinitionId,
            groupInstanceId,
            entryDate,
            exitDate
        );
    }

    private String resolveBasicValue(Person person, String fieldName) {
        if (person.basicProperties == null) return null;
        MongoCollection<Document> instColl = getFieldInstancesCollection();
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && fieldName.equals(def.fieldName)) {
                Document inst = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (inst != null && inst.get("value") != null) {
                    return inst.get("value").toString();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String s && s.isBlank()) return true;
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().allMatch(v -> v == null || (v instanceof String s && s.isBlank()));
        }
        return false;
    }
}
