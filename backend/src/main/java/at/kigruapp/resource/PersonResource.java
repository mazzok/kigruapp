package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.dto.PersonDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import at.kigruapp.security.KeycloakUserService;
import at.kigruapp.service.JsonSchemaValidatorService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.types.ObjectId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Path("/api/v1/persons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PersonResource {

    @Inject
    KeycloakUserService keycloakUserService;

    @Inject
    JsonSchemaValidatorService schemaValidator;

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
    public PersonDTO getFull(@PathParam("id") String id) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        return toFullDTO(person);
    }

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
        String email = null, firstName = null, lastName = null;
        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def != null && def.keycloakMapping != null) {
                FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
                if (inst != null && inst.value != null) {
                    switch (def.keycloakMapping) {
                        case "email" -> email = inst.value.toString();
                        case "firstName" -> firstName = inst.value.toString();
                        case "lastName" -> lastName = inst.value.toString();
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
    public Response update(@PathParam("id") String id, Person update) {
        Person person = Person.findById(new ObjectId(id));
        if (person == null) {
            throw new NotFoundException();
        }
        person.basicProperties = update.basicProperties;
        person.roles = update.roles;
        person.schedules = update.schedules;
        person.duties = update.duties;
        person.finance = update.finance;
        person.customProperties = update.customProperties;
        person.updatedAt = Instant.now();
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
        person.delete();
        return Response.noContent().build();
    }

    private List<FieldRef> createFieldInstances(List<SectionInput> inputs, Instant now) {
        if (inputs == null || inputs.isEmpty()) {
            return new ArrayList<>();
        }
        List<FieldRef> refs = new ArrayList<>();
        for (SectionInput input : inputs) {
            ObjectId defId = new ObjectId(input.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                throw new BadRequestException("Definition not found: " + input.definitionId());
            }
            if (input.value() != null) {
                try {
                    schemaValidator.validate(def.jsonSchema, input.value());
                } catch (JsonSchemaValidatorService.ValidationException e) {
                    throw new BadRequestException(def.fieldName + ": " + e.getMessage());
                }
            }
            FieldInstance inst = new FieldInstance();
            inst.definitionId = defId;
            inst.value = input.value();
            inst.createdAt = now;
            inst.updatedAt = now;
            inst.persist();
            refs.add(new FieldRef(defId, inst.id));
        }
        return refs;
    }

    private void deleteFieldInstances(List<FieldRef> refs) {
        if (refs == null) return;
        for (FieldRef ref : refs) {
            FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
            if (inst != null) {
                inst.delete();
            }
        }
    }

    private PersonDTO toFullDTO(Person person) {
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
        dto.createdAt = person.createdAt != null ? person.createdAt.toString() : null;
        dto.updatedAt = person.updatedAt != null ? person.updatedAt.toString() : null;
        return dto;
    }

    private List<FieldInstanceDTO> resolveRefs(List<FieldRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<FieldInstanceDTO> dtos = new ArrayList<>();
        for (FieldRef ref : refs) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            FieldInstance inst = FieldInstance.findById(ref.fieldInstanceId);
            if (def == null || inst == null) continue;

            FieldInstanceDTO dto = new FieldInstanceDTO();
            dto.id = inst.id.toHexString();
            dto.definitionId = def.id.toHexString();
            dto.fieldName = def.fieldName;
            dto.label = def.label;
            dto.description = def.description;
            dto.jsonSchema = def.jsonSchema;
            dto.required = def.required;
            dto.keycloakMapping = def.keycloakMapping;
            dto.value = inst.value;
            dto.definitionOutdated = def.outdatedAt != null;
            dtos.add(dto);
        }
        return dtos;
    }
}
