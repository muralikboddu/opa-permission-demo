package com.example.permdemo.trades;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Just so `curl localhost:8080/demo/users` tells you what to try. */
@RestController
public class DemoInfoController {

    @GetMapping("/demo/users")
    public List<Map<String, String>> users() {
        return List.of(
                Map.of("user", "alice", "role", "banker", "visibility", "desk_id == FX-NY"),
                Map.of("user", "bob", "role", "region_head", "visibility", "country_code in americas (data.regions, static reference data)"),
                Map.of("user", "carol", "role", "business_manager", "visibility", "owner_id in team-42's roster (resolved via TeamDirectoryClient, cached, input.subject)")
        );
    }
}
