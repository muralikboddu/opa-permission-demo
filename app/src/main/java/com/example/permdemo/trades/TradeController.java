package com.example.permdemo.trades;

import com.example.permdemo.model.ResourceAttributes;
import com.example.permdemo.model.Subject;
import com.example.permdemo.opa.CompiledPredicate;
import com.example.permdemo.opa.ElasticsearchQueryRenderer;
import com.example.permdemo.opa.OpaClient;
import com.example.permdemo.opa.PartialEvalTranslator;
import com.example.permdemo.opa.PermissionOrchestrator;
import com.example.permdemo.opa.SqlPredicateRenderer;
import com.example.permdemo.role.RoleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes all four scenarios from the design discussion behind one
 * controller, differing only in which demo `user` you pass:
 *
 *   GET /trades/{id}?user=alice          point-of-call check on one trade
 *   GET /trades?user=bob&from=&to=       bulk push-down, filtered in Postgres
 *   GET /trades/es?user=carol&from=&to=  the same bulk filter, pushed into Elasticsearch instead
 *   GET /trades/_debug/compile?user=bob  the raw OPA Compile API response, for inspection
 *
 * Try user=alice (banker/desk), user=bob (region_head/americas), and
 * user=carol (business_manager/team-42) against the same endpoints to see
 * the three reference-data scenarios (desk, region, team) return different
 * slices of the same table through the exact same code path.
 */
@RestController
@RequestMapping("/trades")
public class TradeController {

    private final RoleService roleService;
    private final OpaClient opaClient;
    private final PartialEvalTranslator translator;
    private final PermissionOrchestrator orchestrator;
    private final SqlPredicateRenderer sqlRenderer;
    private final ElasticsearchQueryRenderer esRenderer;
    private final TradeRepository tradeRepository;
    private final TradeQueryService tradeQueryService;
    private final ElasticTradeService elasticTradeService;

    public TradeController(RoleService roleService, OpaClient opaClient, PartialEvalTranslator translator,
                            PermissionOrchestrator orchestrator,
                            SqlPredicateRenderer sqlRenderer, ElasticsearchQueryRenderer esRenderer,
                            TradeRepository tradeRepository, TradeQueryService tradeQueryService,
                            ElasticTradeService elasticTradeService) {
        this.roleService = roleService;
        this.opaClient = opaClient;
        this.translator = translator;
        this.orchestrator = orchestrator;
        this.sqlRenderer = sqlRenderer;
        this.esRenderer = esRenderer;
        this.tradeRepository = tradeRepository;
        this.tradeQueryService = tradeQueryService;
        this.elasticTradeService = elasticTradeService;
    }

    // -- Scenario 1: point-of-call check -------------------------------------

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOne(@PathVariable long id, @RequestParam String user) {
        Trade trade = tradeRepository.findById(id).orElse(null);
        if (trade == null) {
            return ResponseEntity.notFound().build();
        }
        Subject subject = roleService.resolveSubject(user);
        ResourceAttributes resource = new ResourceAttributes(trade.getDeskId(), trade.getCountryCode(), trade.getOwnerId());
        OpaClient.PointDecision decision = opaClient.evaluate(subject, resource);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user);
        body.put("allow", decision.allow());
        body.put("reasons", decision.reasons());
        if (decision.allow()) {
            body.put("trade", trade);
        }
        return ResponseEntity.status(decision.allow() ? HttpStatus.OK : HttpStatus.FORBIDDEN).body(body);
    }

    // -- Scenario 2: bulk push-down to Postgres, at 1,000,000-row scale ------

    @GetMapping
    public Map<String, Object> list(@RequestParam String user,
                                     @RequestParam LocalDate from,
                                     @RequestParam LocalDate to,
                                     @RequestParam(defaultValue = "100") int size,
                                     @RequestParam(required = false) LocalDate afterDate,
                                     @RequestParam(required = false) Long afterId) {
        Subject subject = roleService.resolveSubject(user);
        CompiledPredicate predicate = orchestrator.compileAndAudit(subject);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user", user);

        if (predicate instanceof CompiledPredicate.AlwaysDeny) {
            response.put("rows", List.of());
            response.put("appliedFilter", "<always deny -- query was never sent to Postgres>");
            response.put("nextCursor", null);
            return response;
        }

        SqlPredicateRenderer.Rendered rendered = predicate instanceof CompiledPredicate.Conditional cond
                ? sqlRenderer.render(cond)
                : null; // AlwaysAllow: no extra predicate, full date range returned

        var page = tradeQueryService.queryAllowed(from, to, rendered, size, afterDate, afterId);
        response.put("rows", page.rows());
        response.put("appliedFilter", page.appliedSql());
        response.put("nextCursor", page.nextAfterId() == null
                ? null
                : Map.of("afterDate", page.nextAfterDate().toString(), "afterId", page.nextAfterId()));
        return response;
    }

    // -- Scenario 3: the same policy, pushed down into Elasticsearch instead --

    @GetMapping("/es")
    public Object listEs(@RequestParam String user,
                          @RequestParam LocalDate from,
                          @RequestParam LocalDate to,
                          @RequestParam(defaultValue = "100") int size,
                          @RequestParam(required = false) LocalDate afterDate,
                          @RequestParam(required = false) Long afterId) {
        Subject subject = roleService.resolveSubject(user);
        CompiledPredicate predicate = orchestrator.compileAndAudit(subject);

        if (predicate instanceof CompiledPredicate.AlwaysDeny) {
            return Map.of("user", user, "hits", Map.of("total", Map.of("value", 0), "hits", List.of()));
        }

        Map<String, Object> clause = predicate instanceof CompiledPredicate.Conditional cond
                ? esRenderer.render(cond)
                : null; // AlwaysAllow: no extra clause, just the date range filter

        return elasticTradeService.search(from, to, clause, size, afterDate, afterId);
    }

    // -- Inspect exactly what OPA sent back, to sanity-check the translator --

    @GetMapping("/_debug/compile")
    public JsonNode debugCompile(@RequestParam String user) {
        return opaClient.compileRaw(roleService.resolveSubject(user));
    }
}
