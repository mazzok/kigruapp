package at.kigruapp.resource;

import at.kigruapp.dto.FieldInstanceDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.service.JsonSchemaValidatorService;
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
import java.util.Date;
import java.util.List;

@Path("/api/v1/field-instances")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FieldInstanceResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    @Inject
    JsonSchemaValidatorService schemaValidator;

    private MongoCollection<Document> getCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @GET
    @Path("/by-definition/{definitionId}")
    public Response getByDefinitionId(@PathParam("definitionId") String definitionId) {
        Document found = getCollection().find(new Document("definitionId", new ObjectId(definitionId))).first();
        if (found == null) return Response.status(404).build();
        return Response.ok(new Document("id", found.getObjectId("_id").toHexString()).toJson()).build();
    }

    @GET
    @Path("/{id}")
    public FieldInstanceDTO get(@PathParam("id") String id) {
        Document doc = getCollection().find(new Document("_id", new ObjectId(id))).first();
        if (doc == null) {
            throw new NotFoundException();
        }
        FieldInstance inst = FieldInstance.fromDocument(doc);
        FieldDefinition def = FieldDefinition.findById(inst.definitionId);
        return toDTO(def, inst);
    }

    public record BatchItem(String definitionId, Object value) {}

    @POST
    public Response create(BatchItem item) {
        Date now = Date.from(Instant.now());
        MongoCollection<Document> coll = getCollection();

        ObjectId defId = new ObjectId(item.definitionId());
        FieldDefinition def = FieldDefinition.findById(defId);
        if (def == null) {
            return Response.status(400).entity("Definition not found: " + item.definitionId()).build();
        }
        if (def.outdatedAt != null) {
            return Response.status(400).entity("Definition is outdated: " + item.definitionId()).build();
        }
        if (item.value() != null) {
            try {
                schemaValidator.validate(def.jsonSchema, item.value());
            } catch (JsonSchemaValidatorService.ValidationException e) {
                return Response.status(400).entity(def.fieldName + ": " + e.getMessage()).build();
            }
        }

        ObjectId instId = new ObjectId();
        Document doc = new Document("_id", instId)
                .append("definitionId", defId)
                .append("value", item.value())
                .append("createdAt", now)
                .append("updatedAt", now);
        coll.insertOne(doc);

        FieldInstance inst = FieldInstance.fromDocument(doc);
        return Response.status(201).entity(toDTO(def, inst)).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") String id, BatchItem item) {
        MongoCollection<Document> coll = getCollection();
        Document existing = coll.find(new Document("_id", new ObjectId(id))).first();
        if (existing == null) {
            throw new NotFoundException();
        }

        ObjectId defId = new ObjectId(item.definitionId());
        FieldDefinition def = FieldDefinition.findById(defId);
        if (def == null) {
            return Response.status(400).entity("Definition not found: " + item.definitionId()).build();
        }
        if (item.value() != null) {
            try {
                schemaValidator.validate(def.jsonSchema, item.value());
            } catch (JsonSchemaValidatorService.ValidationException e) {
                return Response.status(400).entity(def.fieldName + ": " + e.getMessage()).build();
            }
        }

        Date now = Date.from(Instant.now());
        coll.updateOne(
                new Document("_id", new ObjectId(id)),
                new Document("$set", new Document("value", item.value()).append("updatedAt", now))
        );

        Document updated = coll.find(new Document("_id", new ObjectId(id))).first();
        FieldInstance inst = FieldInstance.fromDocument(updated);
        return Response.ok(toDTO(def, inst)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        MongoCollection<Document> coll = getCollection();
        Document existing = coll.find(new Document("_id", new ObjectId(id))).first();
        if (existing == null) {
            throw new NotFoundException();
        }
        coll.deleteOne(new Document("_id", new ObjectId(id)));
        return Response.noContent().build();
    }

    @PUT
    @Path("/batch")
    public Response batchUpsert(List<BatchItem> items) {
        Date now = Date.from(Instant.now());
        MongoCollection<Document> coll = getCollection();
        List<FieldInstanceDTO> results = new ArrayList<>();

        for (BatchItem item : items) {
            ObjectId defId = new ObjectId(item.definitionId());
            FieldDefinition def = FieldDefinition.findById(defId);
            if (def == null) {
                return Response.status(400).entity("Definition not found: " + item.definitionId()).build();
            }
            if (def.outdatedAt != null) {
                return Response.status(400).entity("Definition is outdated: " + item.definitionId()).build();
            }
            if (item.value() != null) {
                try {
                    schemaValidator.validate(def.jsonSchema, item.value());
                } catch (JsonSchemaValidatorService.ValidationException e) {
                    return Response.status(400).entity(def.fieldName + ": " + e.getMessage()).build();
                }
            }

            ObjectId instId = new ObjectId();
            Document doc = new Document("_id", instId)
                    .append("definitionId", defId)
                    .append("value", item.value())
                    .append("createdAt", now)
                    .append("updatedAt", now);
            coll.insertOne(doc);

            FieldInstance inst = FieldInstance.fromDocument(doc);
            results.add(toDTO(def, inst));
        }
        return Response.ok(results).build();
    }

    private FieldInstanceDTO toDTO(FieldDefinition def, FieldInstance inst) {
        FieldInstanceDTO dto = new FieldInstanceDTO();
        dto.definitionId = def.id.toHexString();
        dto.fieldName = def.fieldName;
        dto.label = def.label;
        dto.description = def.description;
        dto.jsonSchema = def.jsonSchema;
        dto.required = def.required;
        dto.keycloakMapping = def.keycloakMapping;
        dto.definitionOutdated = def.outdatedAt != null;
        if (inst != null) {
            dto.id = inst.id.toHexString();
            dto.value = inst.value;
        }
        return dto;
    }
}
