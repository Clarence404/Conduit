package dev.conduit.server.thingmodel;

/** 物模型属性类型；InfluxDB 3 存储类型见 V0.2-A spec 2.3。 */
public enum DataType {
    DOUBLE,
    INT,
    BOOL,
    TEXT,
    ENUM
}
