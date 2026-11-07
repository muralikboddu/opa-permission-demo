package com.example.permdemo.role;

import com.example.permdemo.model.Subject;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Resolves "who is this user and what are they allowed to reference" --
 * exactly the RoleService from the design we discussed. It's deliberately a
 * hardcoded directory for three demo users, one per scenario:
 *
 *   alice  -- banker on desk FX-NY
 *   bob    -- region head for the Americas
 *   carol  -- business manager of team-42 (roster resolved via
 *             TeamDirectoryClient, i.e. "from an API", not from OPA's data)
 *
 * In a real system this would call your identity/HR service (with its own
 * caching), not a static map -- swap resolveBaseProfile() for that call and
 * everything downstream (OpaClient, the translator, the controllers) is
 * unaffected, because they only ever see the resulting Subject.
 */
@Service
public class RoleService {

    private final TeamDirectoryClient teamDirectoryClient;

    private static final Map<String, String> ROLE_BY_USER = Map.of(
            "alice", "banker",
            "bob", "region_head",
            "carol", "business_manager"
    );

    public RoleService(TeamDirectoryClient teamDirectoryClient) {
        this.teamDirectoryClient = teamDirectoryClient;
    }

    public Subject resolveSubject(String username) {
        String role = ROLE_BY_USER.get(username);
        if (role == null) {
            throw new IllegalArgumentException(
                    "Unknown demo user '" + username + "'. Try alice, bob, or carol.");
        }
        return switch (role) {
            case "banker" -> Subject.banker(username, "FX-NY");
            case "region_head" -> Subject.regionHead(username, "americas");
            case "business_manager" -> {
                String teamId = "team-42";
                // The one line that stands in for "call the team API,
                // cached" -- see TeamDirectoryClient for the cache/TTL.
                var members = teamDirectoryClient.fetchTeamMembers(teamId);
                yield Subject.businessManager(username, teamId, members);
            }
            default -> throw new IllegalStateException("Unhandled role: " + role);
        };
    }
}
