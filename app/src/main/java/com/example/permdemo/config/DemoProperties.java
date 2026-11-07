package com.example.permdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the demo needs that isn't hardcoded: where OPA and Elasticsearch
 * live, and how long the (simulated) external team-directory lookup is
 * cached for.
 */
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String opaUrl = "http://localhost:8181";
    private String elasticsearchUrl = "http://localhost:9200";
    private int teamDirectoryCacheTtlSeconds = 60;

    public String getOpaUrl() {
        return opaUrl;
    }

    public void setOpaUrl(String opaUrl) {
        this.opaUrl = opaUrl;
    }

    public String getElasticsearchUrl() {
        return elasticsearchUrl;
    }

    public void setElasticsearchUrl(String elasticsearchUrl) {
        this.elasticsearchUrl = elasticsearchUrl;
    }

    public int getTeamDirectoryCacheTtlSeconds() {
        return teamDirectoryCacheTtlSeconds;
    }

    public void setTeamDirectoryCacheTtlSeconds(int teamDirectoryCacheTtlSeconds) {
        this.teamDirectoryCacheTtlSeconds = teamDirectoryCacheTtlSeconds;
    }
}
