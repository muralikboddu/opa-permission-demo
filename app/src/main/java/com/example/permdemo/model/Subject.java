package com.example.permdemo.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Everything OPA needs to know about the caller. This is exactly the
 * `input.subject` object sent to OPA on every call -- whether it's a
 * point-of-call check or a Compile API request.
 *
 * Only the fields relevant to a given role are populated; the rest stay
 * null and are omitted from the JSON (see @JsonInclude). That's safe
 * because each Rego rule starts by checking `input.subject.role`, so an
 * absent/irrelevant field on a subject never gets compared against
 * anything for a role it doesn't apply to.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Subject {

    private String username;
    private String role;

    // banker
    private String deskId;

    // region_head
    private String region;

    // business_manager
    private String managedTeamId;
    private List<String> teamMemberIds;

    public static Subject banker(String username, String deskId) {
        Subject s = new Subject();
        s.username = username;
        s.role = "banker";
        s.deskId = deskId;
        return s;
    }

    public static Subject regionHead(String username, String region) {
        Subject s = new Subject();
        s.username = username;
        s.role = "region_head";
        s.region = region;
        return s;
    }

    public static Subject businessManager(String username, String managedTeamId, List<String> teamMemberIds) {
        Subject s = new Subject();
        s.username = username;
        s.role = "business_manager";
        s.managedTeamId = managedTeamId;
        s.teamMemberIds = teamMemberIds;
        return s;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public String getDeskId() {
        return deskId;
    }

    public String getRegion() {
        return region;
    }

    public String getManagedTeamId() {
        return managedTeamId;
    }

    public List<String> getTeamMemberIds() {
        return teamMemberIds;
    }
}
