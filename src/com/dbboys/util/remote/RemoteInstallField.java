package com.dbboys.util.remote;

public final class RemoteInstallField {
    private final String id;
    private final String label;
    private String value;
    private final String description;

    public RemoteInstallField(String id, String label, String value, String description) {
        this.id = id;
        this.label = label;
        this.value = value;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getDescription() {
        return description;
    }
}
