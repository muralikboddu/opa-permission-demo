package com.example.permdemo.opa;

import com.example.permdemo.config.DemoProperties;
import com.example.permdemo.model.ResourceAttributes;
import com.example.permdemo.model.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin REST client for OPA -- deliberately just java.net.http.HttpClient
 * and Jackson rather than a dedicated OPA SDK, so the two calls that matter
 * for this whole design are visible in one place:
 *
 *   evaluate()      -> POST /v1/data/trades/authz         (point-of-call)
 *   compileQueries()-> POST /v1/compile                   (bulk push-down)
 *
 * Both send the exact same `input.subject`; only whether `input.resource`
 * is a concrete object or a declared "unknown" differs. That's the whole
 * trick behind why the single-record check and the bulk filter can never
 * disagree -- see authz.rego's header comment.
 */
@Component
public class OpaClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper;
    private final String baseUrl;

    public OpaClient(ObjectMapper mapper, DemoProperties props) {
        this.mapper = mapper;
        this.baseUrl = props.getOpaUrl();
    }

    public record PointDecision(boolean allow, List<String> reasons) {}

    /** Point-of-call check: "can this subject see this one concrete resource". */
    public PointDecision evaluate(Subject subject, ResourceAttributes resource) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode input = body.putObject("input");
        input.set("subject", mapper.valueToTree(subject));
        input.set("resource", mapper.valueToTree(resource));

        JsonNode response = post("/v1/data/trades/authz", body);
        JsonNode result = response.path("result");
        boolean allow = result.path("allow").asBoolean(false);
        List<String> reasons = new ArrayList<>();
        result.path("reasons").forEach(r -> reasons.add(r.asText()));
        return new PointDecision(allow, reasons);
    }

    /**
     * Raw Compile API response, unmodified -- exposed via
     * GET /trades/_debug/compile so you can see exactly what OPA sent back
     * and compare it against what PartialEvalTranslator expects.
     */
    public JsonNode compileRaw(Subject subject) {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", "data.trades.authz.allow == true");
        ObjectNode input = body.putObject("input");
        input.set("subject", mapper.valueToTree(subject));
        body.putArray("unknowns").add("input.resource");
        return post("/v1/compile", body);
    }

    /** Just the `result.queries` node that PartialEvalTranslator consumes. */
    public JsonNode compileQueries(Subject subject) {
        return compileRaw(subject).path("result").path("queries");
    }

    private JsonNode post(String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "OPA returned HTTP " + response.statusCode() + " for " + path + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to call OPA at " + baseUrl + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling OPA at " + baseUrl + path, e);
        }
    }
}
