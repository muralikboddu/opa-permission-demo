package com.example.permdemo.model;

/**
 * The `input.resource` object for a single, concrete record -- used only on
 * the point-of-call path (POST /v1/data/trades/authz/allow). The bulk path
 * never builds one of these; it marks "input.resource" as unknown instead
 * and lets OPA's Compile API tell us what conditions a resource would have
 * to satisfy.
 */
public class ResourceAttributes {
    private String deskId;
    private String countryCode;
    private String ownerId;

    public ResourceAttributes() {
    }

    public ResourceAttributes(String deskId, String countryCode, String ownerId) {
        this.deskId = deskId;
        this.countryCode = countryCode;
        this.ownerId = ownerId;
    }

    public String getDeskId() {
        return deskId;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getOwnerId() {
        return ownerId;
    }
}
