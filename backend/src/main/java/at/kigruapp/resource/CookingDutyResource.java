package at.kigruapp.resource;

import at.kigruapp.dto.CookingDutyDTO;
import at.kigruapp.entity.FieldDefinition;
import at.kigruapp.entity.FieldInstance;
import at.kigruapp.entity.FieldRef;
import at.kigruapp.entity.Person;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.stream.Collectors;

@Path("/api/v1/cooking-duties")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CookingDutyResource {

    @Inject
    MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    private MongoCollection<Document> getFieldInstancesCollection() {
        return mongoClient.getDatabase(databaseName).getCollection("field_instances");
    }

    @GET
    public List<CookingDutyDTO> list(
            @QueryParam("month") String month,
            @QueryParam("groups") String groupsParam) {

        FieldDefinition cookingDutyDef = FieldDefinition.find("fieldName", "cookingDuty").firstResult();
        if (cookingDutyDef == null) {
            return new ArrayList<>();
        }
        ObjectId cookingDutyDefId = cookingDutyDef.id;

        Set<String> groupFilter = new HashSet<>();
        if (groupsParam != null && !groupsParam.isBlank()) {
            groupFilter.addAll(Arrays.asList(groupsParam.split(",")));
        }

        MongoCollection<Document> instColl = getFieldInstancesCollection();
        List<Person> allPersons = Person.listAll();
        List<CookingDutyDTO> result = new ArrayList<>();

        for (Person person : allPersons) {
            if (person.schedules == null) continue;

            String personName = resolvePersonName(person, instColl);

            for (FieldRef ref : person.schedules) {
                if (!ref.definitionId.equals(cookingDutyDefId)) continue;

                Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
                if (instDoc == null) continue;

                FieldInstance inst = FieldInstance.fromDocument(instDoc);
                if (!(inst.value instanceof Document valueDoc)) continue;

                String date = valueDoc.getString("date");
                if (date == null) continue;

                if (month != null && !month.isBlank() && !date.startsWith(month)) continue;

                List<String> groups = new ArrayList<>();
                Object groupsObj = valueDoc.get("groups");
                if (groupsObj instanceof List<?> groupList) {
                    for (Object g : groupList) {
                        groups.add(g.toString());
                    }
                }

                if (!groupFilter.isEmpty() && groups.stream().noneMatch(groupFilter::contains)) continue;

                Map<String, Boolean> foodProps = new LinkedHashMap<>();
                Object fpObj = valueDoc.get("foodProperties");
                if (fpObj instanceof Document fpDoc) {
                    for (Map.Entry<String, Object> entry : fpDoc.entrySet()) {
                        if (entry.getValue() instanceof Boolean b) {
                            foodProps.put(entry.getKey(), b);
                        }
                    }
                }

                CookingDutyDTO dto = new CookingDutyDTO();
                dto.id = inst.id.toString();
                dto.personId = person.id.toString();
                dto.familyId = person.familyId.toString();
                dto.personName = personName;
                dto.date = date;
                dto.groups = groups;
                dto.description = valueDoc.getString("description");
                dto.foodProperties = foodProps;
                result.add(dto);
            }
        }

        return result;
    }

    private String resolvePersonName(Person person, MongoCollection<Document> instColl) {
        String firstName = "";
        String lastName = "";
        if (person.basicProperties == null) return "";

        for (FieldRef ref : person.basicProperties) {
            FieldDefinition def = FieldDefinition.findById(ref.definitionId);
            if (def == null) continue;

            Document instDoc = instColl.find(new Document("_id", ref.fieldInstanceId)).first();
            if (instDoc == null) continue;

            Object value = instDoc.get("value");
            if ("firstName".equals(def.fieldName) && value instanceof String s) {
                firstName = s;
            } else if ("lastName".equals(def.fieldName) && value instanceof String s) {
                lastName = s;
            }
        }
        return (lastName + " " + firstName).trim();
    }
}
