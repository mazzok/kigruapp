package at.kigruapp.entity;

import org.bson.types.ObjectId;

/**
 * One recipient source of a MailJob: the field instance of a concrete group,
 * team or role. Mirrors {@link FieldRef}'s shape so the POJO codec can map it.
 */
public class RecipientSelection {
    public RecipientKind kind;
    public ObjectId fieldInstanceId;

    public RecipientSelection() {}

    public RecipientSelection(RecipientKind kind, ObjectId fieldInstanceId) {
        this.kind = kind;
        this.fieldInstanceId = fieldInstanceId;
    }
}
