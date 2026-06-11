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
