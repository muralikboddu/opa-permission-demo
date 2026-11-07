package com.example.permdemo.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * The cache that makes the "team roster comes from an external API" scenario
 * cheap: RoleService calls TeamDirectoryClient through this cache instead of
 * hitting the (simulated) HR API on every request. Short TTL here is
 * deliberate -- team membership churns faster than something like a
 * region-to-country mapping, so we don't want to cache it for long. In a
 * real system you'd pair this with event-driven invalidation when
 * membership actually changes, rather than relying on TTL alone.
 */
@Configuration
public class CacheConfig {

    public static final String TEAM_MEMBERS_CACHE = "teamMembers";

    @Bean
    public CacheManager cacheManager(@Autowired DemoProperties props) {
        CaffeineCacheManager manager = new CaffeineCacheManager(TEAM_MEMBERS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(props.getTeamDirectoryCacheTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(10_000));
        return manager;
    }
}
