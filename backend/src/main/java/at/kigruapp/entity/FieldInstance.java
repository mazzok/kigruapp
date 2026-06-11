package at.kigruapp.entity;

import org.bson.Document;
import org.bson.types.ObjectId;

/**
 * FieldInstance uses raw MongoDB driver (not Panache) because
 * the 'value' field can be any BSON type (string, object, array, etc.)
 * and MongoDB's POJO codec cannot handle java.lang.Object.
 */
public class FieldInstance {
    public ObjectId id;
    public ObjectId definitionId;
    public Object value;

    public static FieldInstance fromDocument(Document doc) {
        if (doc == null) return null;
        FieldInstance inst = new FieldInstance();
        inst.id = doc.getObjectId("_id");
        inst.definitionId = doc.getObjectId("definitionId");
        inst.value = doc.get("value");
        return inst;
    }
}
