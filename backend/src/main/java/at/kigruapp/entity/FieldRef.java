package at.kigruapp.entity;

import org.bson.types.ObjectId;

public class FieldRef {
    public ObjectId definitionId;
    public ObjectId fieldInstanceId;

    public FieldRef() {}

    public FieldRef(ObjectId definitionId, ObjectId fieldInstanceId) {
        this.definitionId = definitionId;
        this.fieldInstanceId = fieldInstanceId;
    }
}
