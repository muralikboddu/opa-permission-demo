package com.example.permdemo.role;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.example.permdemo.config.CacheConfig.TEAM_MEMBERS_CACHE;

/**
 * Stands in for a real HR / org-chart API that owns team rosters. This is
 * the "team membership comes from an API, not a static file" scenario: the
 * roster is deliberately NOT loaded into OPA's `data` document. Instead,
 * RoleService calls this (through the cache below) and hands the resolved
 * roster to OPA as part of `input.subject` on each request -- see the
 * conversation notes in RoleService for why that's the right split for a
 * per-manager, moderate-sized roster like this one.
 *
 * The artificial sleep() is standing in for real network latency, so you
 * can actually see the cache paying for itself: the first call for a given
 * team is slow, every call within the TTL is instant.
 */
@Component
public class TeamDirectoryClient {

    private static final Map<String, List<String>> TEAMS = Map.of(
            "team-42", List.of("p101", "p102", "p103", "p104", "p105",
                                "p106", "p107", "p108", "p109", "p110")
    );

    @Cacheable(TEAM_MEMBERS_CACHE)
    public List<String> fetchTeamMembers(String teamId) {
        simulateNetworkLatency();
        return TEAMS.getOrDefault(teamId, List.of());
    }

    private void simulateNetworkLatency() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
