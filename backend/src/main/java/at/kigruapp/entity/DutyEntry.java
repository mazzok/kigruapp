package at.kigruapp.entity;

import org.bson.types.ObjectId;
import java.util.ArrayList;
import java.util.List;

public class DutyEntry {
    public String name;
    public List<ObjectId> definitionIds = new ArrayList<>();

    public DutyEntry() {}

    public DutyEntry(String name) {
        this.name = name;
    }
}
