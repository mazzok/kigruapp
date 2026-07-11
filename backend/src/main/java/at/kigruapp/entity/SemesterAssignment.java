package at.kigruapp.entity;

import org.bson.Document;
import org.bson.types.ObjectId;

public class SemesterAssignment {
    public ObjectId id;
    public ObjectId personId;
    public ObjectId semesterId;
    public String section;
    public ObjectId definitionId;
    public ObjectId fieldInstanceId;

    public static SemesterAssignment fromDocument(Document doc) {
        if (doc == null) return null;
        SemesterAssignment sa = new SemesterAssignment();
        sa.id = doc.getObjectId("_id");
        sa.personId = doc.getObjectId("personId");
        sa.semesterId = doc.getObjectId("semesterId");
        sa.section = doc.getString("section");
        sa.definitionId = doc.getObjectId("definitionId");
        sa.fieldInstanceId = doc.getObjectId("fieldInstanceId");
        return sa;
    }

    public Document toDocument() {
        return new Document("_id", id != null ? id : new ObjectId())
                .append("personId", personId)
                .append("semesterId", semesterId)
                .append("section", section)
                .append("definitionId", definitionId)
                .append("fieldInstanceId", fieldInstanceId);
    }
}
