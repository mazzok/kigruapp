package at.kigruapp.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;

@MongoEntity(collection = "currencies")
public class Currency extends PanacheMongoEntity {
    public String code;
    public String symbol;
}
