# Codebase Deep Dive

This document explains every component in the codebase at a low level: what it does, why it is designed that way, how it connects to the components around it, and what the data looks like at each stage. Read it top to bottom for a full mental model, or jump to a specific section by name.

---

## Table of Contents

1. [System overview and data flow](#1-system-overview-and-data-flow)
2. [The Rego policy](#2-the-rego-policy--policiestradesauthzrego)
3. [Reference data](#3-reference-data--policiesdatajson)
4. [Configuration layer](#4-configuration-layer)
5. [Domain models](#5-domain-models)
6. [RoleService — who is the caller?](#6-roleservice--who-is-the-caller)
7. [TeamDirectoryClient — simulated HR API with cache](#7-teamdirectoryclient--simulated-hr-api-with-cache)
8. [OpaClient — the two OPA API calls](#8-opaclient--the-two-opa-api-calls)
9. [PartialEvalTranslator — walking OPA's AST](#9-partialevaltranslator--walking-opas-ast)
10. [CompiledPredicate — the engine-agnostic IR](#10-compiledpredicate--the-engine-agnostic-ir)
11. [PermissionOrchestrator — compile + translate + audit](#11-permissionorchestrator--compile--translate--audit)
12. [SqlPredicateRenderer — IR to SQL WHERE clause](#12-sqlpredicaterenderer--ir-to-sql-where-clause)
13. [ElasticsearchQueryRenderer — IR to ES Query DSL](#13-elasticsearchqueryrenderer--ir-to-es-query-dsl)
14. [TradeController — the four endpoints](#14-tradecontroller--the-four-endpoints)
15. [TradeQueryService — push-down query to H2/Postgres](#15-tradequeryservice--push-down-query-to-h2postgres)
16. [ElasticTradeService — push-down query to Elasticsearch](#16-elastictradeservice--push-down-query-to-elasticsearch)
17. [GlobalExceptionHandler](#17-globalexceptionhandler)
18. [Seed data](#18-seed-data--srcmainresourcesdatasql)
19. [OPA config and decision logging](#19-opa-config-and-decision-logging--opa-configyaml)
20. [Test suite](#20-test-suite)
21. [End-to-end worked example](#21-end-to-end-worked-example)

---

## 1. System overview and data flow

There are two distinct runtime paths through the system. They share the same Rego policy and the same `input.subject` structure, but differ in what they do with `input.resource`.

### Path A — point-of-call check (single trade)

```
HTTP GET /trades/{id}?user=alice
        │
        ▼
TradeController.getOne()
        │
        ├─► TradeRepository.findById(id)         [H2: fetch the trade row]
        │
        ├─► RoleService.resolveSubject("alice")  [builds input.subject]
        │
        ├─► OpaClient.evaluate(subject, resource)
        │       │
        │       └─► POST /v1/data/trades/authz   [OPA: full evaluation]
        │               input.subject = alice's subject JSON
        │               input.resource = the trade's desk_id/country_code/owner_id
        │               ← {allow: true, reasons: ["desk_match"]}
        │
        └─► HTTP 200 {allow, reasons, trade} or HTTP 403 {allow, reasons}
```

### Path B — bulk push-down (trade list)

```
HTTP GET /trades?user=bob&from=2024-01-01&to=2024-12-31
        │
        ▼
TradeController.list()
        │
        ├─► RoleService.resolveSubject("bob")    [builds input.subject]
        │
        ├─► PermissionOrchestrator.compileAndAudit(subject)
        │       │
        │       ├─► OpaClient.compileQueries(subject)
        │       │       │
        │       │       └─► POST /v1/compile      [OPA: partial evaluation]
        │       │               input.subject = bob's subject JSON
        │       │               unknowns = ["input.resource"]
        │       │               ← result.queries = AST of residual conditions
        │       │
        │       ├─► PartialEvalTranslator.translate(queries)
        │       │       └─► CompiledPredicate.Conditional([
        │       │               [Condition("country_code", IN, ["US","CA","MX",...])]
        │       │           ])
        │       │
        │       ├─► emitAuditLog(decisionId, subject, predicate)
        │       │       └─► AUDIT logger: JSON entry type=partial_eval
        │       │
        │       └─► returns CompiledPredicate
        │
        ├─► SqlPredicateRenderer.render(predicate)
        │       └─► Rendered("(country_code IN (?,?,?,...))", ["US","CA",...])
        │
        ├─► TradeQueryService.queryAllowed(from, to, rendered, size, ...)
        │       └─► JdbcTemplate: SELECT ... WHERE trade_date BETWEEN ? AND ?
        │                         AND (country_code IN (?,?,?,...))
        │                         ORDER BY trade_date, id LIMIT ?
        │
        └─► HTTP 200 {rows, appliedFilter, nextCursor}
```

The critical design constraint: **Path A and Path B use the same Rego `allow` rule**. Path A evaluates it with a concrete `input.resource`. Path B evaluates it with `input.resource` as an unknown — OPA partially evaluates the rule and returns the residual conditions on `input.resource` that would have to be true for `allow` to be `true`. The app then pushes those conditions into the database query.

This guarantees that the trade list and the single-trade view can never disagree about what a user is allowed to see.

---

## 2. The Rego policy — `policies/trades/authz.rego`

```rego
package trades.authz
```

The package name `trades.authz` determines the URL path at which OPA serves this policy: `POST /v1/data/trades/authz`. The app calls `POST /v1/data/trades/authz` for full evaluation and `POST /v1/compile` with `query = "data.trades.authz.allow == true"` for partial evaluation.

### The `allow` rule

```rego
default allow := false
```

This sets `allow` to `false` if no other rule produces `true`. Without this, an undefined result (no rules matched) would also be `false`, but stating it explicitly makes the deny-by-default intent clear to anyone reading the policy.

```rego
allow if {
    input.subject.role == "banker"
    input.resource.desk_id == input.subject.desk_id
}
```

**Rule 1 — desk match.** The body is an implicit AND: both conditions must be true. `input.subject.role` is a known value (passed in by the app). `input.subject.desk_id` is also known. `input.resource.desk_id` is either known (point-of-call) or unknown (partial eval). When `input.resource` is unknown, OPA sees: `input.subject.role == "banker"` resolves to `true` (because it is), `input.subject.desk_id` resolves to `"FX-NY"` (because it is), so the residual left for the unknown is `input.resource.desk_id == "FX-NY"` — a single equality condition.

```rego
allow if {
    input.subject.role == "region_head"
    input.resource.country_code in data.regions[input.subject.region]
}
```

**Rule 2 — region match.** `data.regions` is loaded from `policies/data.json`. `input.subject.region` is known (`"americas"`), so `data.regions["americas"]` resolves to the concrete array `["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]` at compile time. The residual for the unknown `input.resource` is `input.resource.country_code in ["US","CA",...]` — an IN-membership condition. This is the key partial evaluation demo: a lookup against static reference data is fully eliminated at policy compile time.

```rego
allow if {
    input.subject.role == "business_manager"
    input.resource.owner_id in input.subject.team_member_ids
}
```

**Rule 3 — team match.** `input.subject.team_member_ids` is part of the known subject (not in `unknowns`), so it resolves to the concrete team roster `["p101","p102",...]` at compile time. The residual is `input.resource.owner_id in ["p101","p102",...]`. The team roster comes from an external HR API (via `TeamDirectoryClient`), not from OPA's `data` document — the app resolves it and passes it as `input.subject` on each request.

### The `reasons` set

```rego
reasons contains "desk_match" if {
    input.subject.role == "banker"
    input.resource.desk_id == input.subject.desk_id
}
```

`reasons` is a set that accumulates the names of whichever rules fired. It is only used by the point-of-call check path (`POST /v1/data/trades/authz` returns `{allow, reasons}`) — the bulk path never builds `reasons` because it only compiles `allow`, not the full response. Including `reasons` in the policy output has zero cost on the bulk path.

### Why one `allow` rule with OR branches rather than separate rules

Rego allows multiple `allow` definitions — they are OR'd automatically. This is exactly what this policy does: three separate `allow if { ... }` blocks that are semantically OR'd. Any one being true makes `allow` true. This is idiomatic Rego and directly matches the "OR of visibility rules" design. If you add a fourth rule, it participates automatically.

---

## 3. Reference data — `policies/data.json`

```json
{
  "regions": {
    "americas": ["US", "CA", "MX", "BR", "AR", "CL", "CO", "PE", "CR", "PA"],
    "emea":     ["GB", "DE", "FR", "CH", "AE", "ZA", "NL", "ES", "IT", "SA"],
    "apac":     ["SG", "JP", "HK", "AU", "IN", "CN", "KR", "MY", "TH", "NZ"]
  }
}
```

When OPA loads a directory, any `data.json` file at the root becomes the `data` document available in all policies. This file is loaded alongside `authz.rego` when you run:

```bash
opa run --server ./policies
```

Inside the policy, `data.regions` refers to this document. At partial evaluation time, `data.regions["americas"]` is resolved to the concrete array — OPA treats `data` as fully known, the same way it treats `input.subject`. Only `input.resource` is declared as unknown. This is what allows the reference data lookup to be eliminated at compile time.

**Why is region data here instead of in TeamDirectoryClient?** Region-to-country mappings are static and change rarely (a country joins a region once per decade). Team rosters change frequently (people join/leave teams weekly). Static data belongs in OPA's `data` document. Dynamic data that requires a live API call belongs in `input.subject`, resolved by the app before each OPA call.

---

## 4. Configuration layer

### `DemoProperties.java`

```java
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {
    private String opaUrl = "http://localhost:8181";
    private String elasticsearchUrl = "http://localhost:9200";
    private int teamDirectoryCacheTtlSeconds = 60;
}
```

`@ConfigurationProperties(prefix = "demo")` tells Spring Boot to bind any `demo.*` properties from `application.yml` to this class. The defaults (`localhost:8181`, `localhost:9200`, 60 seconds) work for local development. In Docker Compose they are overridden via environment variables (`OPA_URL`, `ELASTICSEARCH_URL`).

`@ConfigurationPropertiesScan` on the main application class tells Spring to discover and register all `@ConfigurationProperties` classes automatically, so `DemoProperties` does not need an explicit `@Bean` method.

### `CacheConfig.java`

```java
@Bean
public CacheManager cacheManager(@Autowired DemoProperties props) {
    CaffeineCacheManager manager = new CaffeineCacheManager(TEAM_MEMBERS_CACHE);
    manager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(props.getTeamDirectoryCacheTtlSeconds(), TimeUnit.SECONDS)
            .maximumSize(10_000));
    return manager;
}
```

Creates a Caffeine cache named `"teamMembers"`. `expireAfterWrite` means entries expire a fixed duration after they were written — appropriate for a team roster where you want a bounded staleness window regardless of access frequency. `maximumSize(10_000)` caps memory in case there are many teams. The TTL is read from `DemoProperties` so it can be tuned via `application.yml` or an environment variable.

`@EnableCaching` on the main application class activates Spring's caching infrastructure so `@Cacheable` annotations (on `TeamDirectoryClient`) are honoured.

### `application.yml`

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
  datasource:
    url: jdbc:h2:mem:permdemo;DB_CLOSE_DELAY=-1
  jpa:
    defer-datasource-initialization: true
    hibernate:
      ddl-auto: create
  sql:
    init:
      mode: always
```

**`property-naming-strategy: SNAKE_CASE`** — This is critical. The Rego policy uses snake_case field names (`desk_id`, `country_code`, `owner_id`, `team_member_ids`). Java uses camelCase (`deskId`, `countryCode`, `ownerId`, `teamMemberIds`). Without this setting, Jackson would serialize `deskId` as `"deskId"` in the JSON sent to OPA, and the policy would never match because `input.subject.deskId` is not the same path as `input.subject.desk_id`. This single setting ensures every Java → JSON serialization in the app (Subject, ResourceAttributes, response bodies) uses snake_case, aligning with the Rego field paths.

**`DB_CLOSE_DELAY=-1`** — Keeps the H2 in-memory database alive for the lifetime of the JVM. Without this, H2 drops the database when the last connection closes, which would wipe the seed data between requests.

**`defer-datasource-initialization: true`** — Ensures Hibernate creates the schema (DDL) before `data.sql` runs. Without this, Spring Boot 2.5+ runs `data.sql` before Hibernate initializes the schema, causing "table not found" errors on insert.

**`ddl-auto: create`** — Hibernate creates the `trades` table from the `@Entity` annotations at startup. Combined with `defer-datasource-initialization`, this means: (1) Hibernate creates the table, (2) Spring runs `data.sql` to insert the seed rows.

---

## 5. Domain models

### `Subject.java`

Represents everything OPA needs to know about the caller. This becomes `input.subject` in every OPA request.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Subject {
    private String username;
    private String role;
    private String deskId;          // banker only
    private String region;          // region_head only
    private String managedTeamId;   // business_manager only
    private List<String> teamMemberIds; // business_manager only
}
```

`@JsonInclude(NON_NULL)` ensures only the fields relevant to a given role are included in the JSON. A banker's subject serializes as `{"username":"alice","role":"banker","desk_id":"FX-NY"}` — it never includes `region` or `team_member_ids` because they are null. This matters for the Rego policy: each rule starts by checking `input.subject.role`, so an absent field on a subject of the wrong role is never evaluated against anything.

With `SNAKE_CASE` naming:
- `deskId` → `"desk_id"` in JSON → matches `input.subject.desk_id` in Rego ✓
- `teamMemberIds` → `"team_member_ids"` in JSON → matches `input.subject.team_member_ids` in Rego ✓

Factory methods (`Subject.banker()`, `Subject.regionHead()`, `Subject.businessManager()`) ensure only the relevant fields are populated for each role.

### `ResourceAttributes.java`

Represents the trade resource in a point-of-call check. This becomes `input.resource` in a full evaluation call.

```java
public class ResourceAttributes {
    private String deskId;
    private String countryCode;
    private String ownerId;
}
```

With `SNAKE_CASE` naming:
- `deskId` → `"desk_id"` → matches `input.resource.desk_id` in Rego ✓
- `countryCode` → `"country_code"` → matches `input.resource.country_code` ✓
- `ownerId` → `"owner_id"` → matches `input.resource.owner_id` ✓

In a bulk/compile request, `input.resource` is never constructed as a Java object. Instead, the string `"input.resource"` is added to the `unknowns` array in the OPA compile request body, telling OPA to treat the entire `input.resource` path as unknown.

---

## 6. RoleService — who is the caller?

```java
@Service
public class RoleService {

    private static final Map<String, String> ROLE_BY_USER = Map.of(
            "alice", "banker",
            "bob",   "region_head",
            "carol", "business_manager"
    );

    public Subject resolveSubject(String username) {
        String role = ROLE_BY_USER.get(username);
        if (role == null) throw new IllegalArgumentException("Unknown demo user...");
        return switch (role) {
            case "banker" -> Subject.banker(username, "FX-NY");
            case "region_head" -> Subject.regionHead(username, "americas");
            case "business_manager" -> {
                var members = teamDirectoryClient.fetchTeamMembers("team-42");
                yield Subject.businessManager(username, "team-42", members);
            }
        };
    }
}
```

`resolveSubject` is the boundary where a username string becomes a fully-enriched `Subject` that OPA can evaluate. In a real system this would call an identity service (LDAP, Okta, internal HR API). The key design constraint: **the Subject is fully resolved before OPA is called**. OPA never makes outbound calls to fetch role information. OPA only evaluates what it is given.

For `business_manager`, the team roster is fetched here via `TeamDirectoryClient` (which may hit the cache or simulate an API call), and then passed into the Subject as `team_member_ids`. This is different from how region data works: region data is in OPA's `data` document (static, loaded once). Team rosters are in `input.subject` (dynamic, resolved per-request with caching). The choice of where to put reference data has a direct impact on the partial evaluation output — in both cases OPA resolves the values to concrete arrays at compile time.

---

## 7. TeamDirectoryClient — simulated HR API with cache

```java
@Component
public class TeamDirectoryClient {

    private static final Map<String, List<String>> TEAMS = Map.of(
            "team-42", List.of("p101","p102","p103","p104","p105",
                               "p106","p107","p108","p109","p110")
    );

    @Cacheable(TEAM_MEMBERS_CACHE)
    public List<String> fetchTeamMembers(String teamId) {
        simulateNetworkLatency();
        return TEAMS.getOrDefault(teamId, List.of());
    }

    private void simulateNetworkLatency() {
        Thread.sleep(150);
    }
}
```

`@Cacheable(TEAM_MEMBERS_CACHE)` intercepts the method call. On the first call for a given `teamId`, Spring calls the actual method body, stores the result in the Caffeine cache under the key `teamId`, and returns it. On subsequent calls within the TTL, Spring returns the cached value without calling the method body. The 150ms sleep is there to make the caching behaviour observable: the first call for a team is slow, every subsequent call within the TTL is instant.

The cache TTL is 60 seconds by default (`DemoProperties.teamDirectoryCacheTtlSeconds`). In a real system you would also add event-driven invalidation (e.g. a Kafka topic publishing team membership change events) rather than relying solely on TTL.

**Why isn't team data in OPA's `data` document?** Because team rosters require a live API call to an external system. OPA's `data` document is loaded at startup and refreshed via bundle polling — it is not suitable for data that changes frequently and requires a per-request authoritative source. The pattern here is: resolve dynamic data in the app, inject it as `input.subject`, let OPA evaluate it as known data.

---

## 8. OpaClient — the two OPA API calls

`OpaClient` is the only class that talks to OPA. It uses `java.net.http.HttpClient` and Jackson directly — no OPA SDK, so the two API calls are visible in full.

### `evaluate()` — full evaluation (point-of-call)

```java
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
```

**Request body sent to OPA:**
```json
{
  "input": {
    "subject": {"username": "alice", "role": "banker", "desk_id": "FX-NY"},
    "resource": {"desk_id": "FX-NY", "country_code": "DE", "owner_id": "e001"}
  }
}
```

**OPA response:**
```json
{
  "result": {
    "allow": true,
    "reasons": ["desk_match"]
  }
}
```

OPA evaluates the full policy against the concrete input and returns all defined values in the `trades.authz` package. `allow` and `reasons` are both defined in the package, so both appear in `result`.

### `compileRaw()` — partial evaluation (bulk push-down)

```java
public JsonNode compileRaw(Subject subject) {
    ObjectNode body = mapper.createObjectNode();
    body.put("query", "data.trades.authz.allow == true");
    ObjectNode input = body.putObject("input");
    input.set("subject", mapper.valueToTree(subject));
    body.putArray("unknowns").add("input.resource");
    return post("/v1/compile", body);
}
```

**Request body sent to OPA:**
```json
{
  "query": "data.trades.authz.allow == true",
  "input": {
    "subject": {"username": "bob", "role": "region_head", "region": "americas"}
  },
  "unknowns": ["input.resource"]
}
```

The `unknowns` array tells OPA which parts of the input are not yet known. Everything not listed in `unknowns` is treated as fully known — including `input.subject` and `data`. OPA partially evaluates `data.trades.authz.allow == true` given these knowns, and returns only the conditions on `input.resource` that could not yet be resolved.

**OPA response (abbreviated):**
```json
{
  "result": {
    "queries": [
      [
        {
          "terms": [
            {"type": "ref", "value": [{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
            {"type": "ref", "value": [{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"country_code"}]},
            {"type": "array", "value": [{"type":"string","value":"US"}, ...]}
          ]
        }
      ]
    ]
  }
}
```

`result.queries` is an array of arrays (OR of ANDs). Each inner array is one OR-branch (a conjunction of conditions). Each element of an inner array is one expression (a condition).

**Special cases:**
- `result.queries` is **absent or null** → the policy is always false for this subject (AlwaysDeny). No query should be run.
- `result.queries` contains **an empty inner array `[[]]`** → one of the OR branches had no remaining conditions after partial evaluation, meaning the subject is unconditionally allowed to see everything (AlwaysAllow).

### `compileQueries()` and `compileRaw()`

`compileRaw()` returns the full OPA response as a `JsonNode`. It is exposed verbatim via `GET /trades/_debug/compile` so you can inspect exactly what OPA sent back.

`compileQueries()` just extracts `result.queries` from the raw response and passes it to `PartialEvalTranslator`. The split exists so the debug endpoint can return the full response while the translator only receives the part it cares about.

---

## 9. PartialEvalTranslator — walking OPA's AST

This is the most complex class in the codebase. It converts OPA's Compile API response — a JSON AST using OPA's internal term representation — into the engine-agnostic `CompiledPredicate` IR.

### OPA's expression shape

Each expression in `result.queries[i]` has this shape:

```json
{
  "index": 0,
  "negated": false,
  "terms": [ <operator>, <operand1>, <operand2> ]
}
```

Every expression is a **binary operator** applied to two operands. The operator is always the first element of `terms`. Each term has a `type` and a `value`.

### Operator terms

```json
{"type": "ref", "value": [{"type": "var", "value": "eq"}]}
```

A ref with a single var segment is a builtin operator. Known builtins:
- `eq`, `equal` → `==`
- `neq`, `ne` → `!=`
- `lt` → `<`, `lte`/`le` → `<=`, `gt` → `>`, `gte`/`ge` → `>=`

The `in` operator is special. Rego's `x in y` desugars to the internal builtin `internal.member_2`:

```json
{"type": "ref", "value": [
  {"type": "var",    "value": "internal"},
  {"type": "string", "value": "member_2"}
]}
```

The translator detects this with `opName.endsWith("member_2")`.

### Operand terms

**A ref into `input.resource.*`** (the unknown):

```json
{"type": "ref", "value": [
  {"type": "var",    "value": "input"},
  {"type": "string", "value": "resource"},
  {"type": "string", "value": "country_code"}
]}
```

The translator converts this to the dotted path `"input.resource.country_code"` via `refToDottedName()`, then strips the `"input.resource."` prefix to get the field name `"country_code"`.

**A string/number/boolean literal:**

```json
{"type": "string", "value": "FX-NY"}
```

**An array of literals** (the right-hand side of `in`):

```json
{"type": "array", "value": [
  {"type": "string", "value": "US"},
  {"type": "string", "value": "CA"},
  ...
]}
```

### Translation logic (step by step)

```java
public CompiledPredicate translate(JsonNode queriesNode) {
    if (queriesNode == null || !queriesNode.isArray() || queriesNode.isEmpty()) {
        return new AlwaysDeny();                    // no queries → always deny
    }
    for (JsonNode branch : queriesNode) {           // outer loop: OR branches
        if (branch.isEmpty()) return new AlwaysAllow(); // empty branch → always allow
        for (JsonNode expr : branch) {              // inner loop: AND conditions
            andConditions.add(translateExpr(expr));
        }
        orBranches.add(andConditions);
    }
    return new Conditional(orBranches);
}
```

For each expression, `translateExpr()`:
1. Reads `terms[0]` → operator name (via `refToDottedName`)
2. If operator is `member_2` → IN condition: left=resource field ref, right=array literal
3. Otherwise → comparison condition: normalize so the resource ref is always "left" (flip the operator if needed)
4. Apply `negated` flag: negated `==` becomes `!=`

The ref-to-dotted-name conversion handles both single-var refs (`eq` → `"eq"`) and multi-segment refs (`input.resource.country_code` → `"input.resource.country_code"`).

### Why this needs to fail loudly

If OPA's compile response has a shape the translator doesn't recognize (a new operator, a nested ref, a set literal), it throws `UnsupportedRegoConstructException` with the raw JSON node attached. The alternative — silently ignoring an unrecognized term — would produce a WHERE clause that is less restrictive than the policy, exposing data the user is not authorized to see. Loud failure with the raw JSON is the safe choice.

---

## 10. CompiledPredicate — the engine-agnostic IR

```java
public sealed interface CompiledPredicate {
    record AlwaysAllow()  implements CompiledPredicate {}
    record AlwaysDeny()   implements CompiledPredicate {}
    record Conditional(List<List<Condition>> orOfAndConditions) implements CompiledPredicate {}

    record Condition(String field, Op op, List<String> values) {
        public String value() { return values.get(0); }
    }

    enum Op { EQ, NEQ, LT, LTE, GT, GTE, IN, NOT_IN }
}
```

This is an Intermediate Representation (IR) that sits between OPA's AST and any specific query language. `PartialEvalTranslator` produces it; `SqlPredicateRenderer` and `ElasticsearchQueryRenderer` consume it.

**`AlwaysAllow`** — OPA returned an empty branch (`[[]]`), meaning this subject is unconditionally allowed. No filter predicate is added to the query.

**`AlwaysDeny`** — OPA returned no queries (absent or null `result.queries`), meaning no resource can satisfy the policy for this subject. The query is not sent to the database at all; an empty result is returned immediately.

**`Conditional`** — The normal case. `orOfAndConditions` is a `List<List<Condition>>` — an OR of ANDs (Disjunctive Normal Form). Each outer list element is one OR-branch. Each inner list element is one AND-condition within that branch. For the three demo users, all three produce a single-branch single-condition result (one outer list, one inner list).

**`sealed interface`** — Using a sealed interface means the compiler enforces that every pattern-match switch covers all three cases. Neither `SqlPredicateRenderer` nor `ElasticsearchQueryRenderer` can forget to handle `AlwaysAllow` or `AlwaysDeny`.

---

## 11. PermissionOrchestrator — compile + translate + audit

```java
@Component
public class PermissionOrchestrator {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.partial_eval");

    public CompiledPredicate compileAndAudit(Subject subject) {
        String decisionId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        CompiledPredicate predicate = translator.translate(opaClient.compileQueries(subject));
        emitAuditLog(decisionId, timestamp, subject, predicate);
        return predicate;
    }
}
```

This class exists to keep three concerns together: calling OPA compile, translating the result, and emitting a compliance audit log — without polluting `TradeController` with that logic.

### Audit log format

Every call emits one structured JSON entry to the `audit.partial_eval` logger. The logger name is distinct from the application logger (`com.example.permdemo`), making it easy to route audit logs to a separate appender or external system.

```json
{
  "decision_id": "56f3c467-71eb-4404-a633-4a762d963418",
  "timestamp": "2026-11-07T18:19:42.915698Z",
  "type": "openpolicyagent.org/partial_eval",
  "path": "trades/authz",
  "subject": {
    "username": "bob",
    "role": "region_head",
    "region": "americas"
  },
  "result": {
    "outcome": "conditional",
    "filter": "country_code IN [US, CA, MX, BR, AR, CL, CO, PE, CR, PA]",
    "branch_count": 1
  }
}
```

**`decision_id`** — A UUID generated per compile call. Use this to correlate the partial eval audit log with the downstream query log (the actual SQL that ran), which can be captured from the JDBC layer or database query log.

**`type: "openpolicyagent.org/partial_eval"`** — Matches the namespace convention of OPA's own decision log entries (`type: "openpolicyagent.org/decision_logs"`). Both types can be shipped to the same compliance sink and distinguished by the `type` field.

**`result.filter`** — Human-readable description of the residual (e.g. `"country_code IN [US, CA, MX, ...]"`). This is what actually goes into the database query. It answers the compliance question: "what data was this user allowed to see in this request?"

**`result.outcome`** — `conditional` (the normal case), `always_allow` (the subject is unconditionally permitted), or `always_deny` (the subject is denied everything — the query was not sent to the database).

The audit log is emitted **after** translation succeeds. If `PartialEvalTranslator` throws, no audit log is emitted — the exception propagates to `GlobalExceptionHandler` which returns a 502. This is intentional: a failed translation means no data was returned, so there is nothing to audit. The error itself is logged by the standard application logger.

---

## 12. SqlPredicateRenderer — IR to SQL WHERE clause

```java
@Component
public class SqlPredicateRenderer {

    private static final Set<String> ALLOWED_COLUMNS =
            Set.of("desk_id", "country_code", "owner_id");

    public record Rendered(String sql, List<Object> params) {}

    public Rendered render(Conditional predicate) {
        List<Object> params = new ArrayList<>();
        String sql = predicate.orOfAndConditions().stream()
                .map(and -> renderAnd(and, params))
                .collect(Collectors.joining(" OR ", "(", ")"));
        return new Rendered(sql, params);
    }
}
```

**Output for bob:** `"(country_code IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?))"` with params `["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]`.

**Output for a two-branch case (hypothetical):** `"(desk_id = ?) OR (country_code IN (?,?))"` with params `["FX-NY","US","CA"]`.

### The column allowlist

```java
private static final Set<String> ALLOWED_COLUMNS =
        Set.of("desk_id", "country_code", "owner_id");

private String column(String field) {
    if (!ALLOWED_COLUMNS.contains(field)) {
        throw new UnsupportedRegoConstructException(
                "policy referenced a field that isn't an allow-listed trades column", field);
    }
    return field;
}
```

Values are always parameterized via `?` placeholders — that prevents SQL injection of values. But column names cannot be parameterized in JDBC. A bug or deliberate malicious change to the Rego policy could reference a field like `1=1; DROP TABLE trades; --` as a field name, which would appear verbatim in the SQL string. The allowlist prevents this: if the translator produces a `Condition` with a field not in `ALLOWED_COLUMNS`, the renderer throws rather than interpolating it into SQL.

### Parameterized rendering

Values are never interpolated into the SQL string. Every value is added to the `params` list and represented as `?` in the SQL. `TradeQueryService` passes this to `JdbcTemplate.query(sql, params.toArray())` which uses a `PreparedStatement` — the JDBC driver handles quoting and escaping. This is injection-safe regardless of what values OPA returns.

---

## 13. ElasticsearchQueryRenderer — IR to ES Query DSL

```java
public Map<String, Object> render(Conditional predicate) {
    List<Object> should = new ArrayList<>();
    for (List<Condition> and : predicate.orOfAndConditions()) {
        should.add(renderAnd(and));
    }
    return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
}
```

**Mapping from IR to ES:**

| `Op` | ES clause |
|---|---|
| `EQ` | `{"term": {"field": "value"}}` |
| `NEQ` | `{"bool": {"must_not": [{"term": ...}]}}` |
| `IN` | `{"terms": {"field": ["v1","v2",...]}}` |
| `NOT_IN` | `{"bool": {"must_not": [{"terms": ...}]}}` |
| `LT/LTE/GT/GTE` | `{"range": {"field": {"lt/lte/gt/gte": "value"}}}` |

**Output for bob (single-branch, IN condition):**
```json
{
  "bool": {
    "should": [
      {
        "bool": {
          "filter": [
            {"terms": {"country_code": ["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]}}
          ]
        }
      }
    ],
    "minimum_should_match": 1
  }
}
```

This clause is inserted into the ES `bool.filter` of the full search query in `ElasticTradeService`. Note there is no column allowlist here — Elasticsearch rejects queries against unknown fields (they simply match nothing), so the injection risk is lower than with SQL. For production use, adding an equivalent allowlist would be a sensible defence-in-depth measure.

---

## 14. TradeController — the four endpoints

```java
@RestController
@RequestMapping("/trades")
public class TradeController {
```

### `GET /trades/{id}?user=alice` — point-of-call check

```java
@GetMapping("/{id}")
public ResponseEntity<Map<String, Object>> getOne(@PathVariable long id, @RequestParam String user) {
    Trade trade = tradeRepository.findById(id).orElse(null);
    if (trade == null) return ResponseEntity.notFound().build();

    Subject subject = roleService.resolveSubject(user);
    ResourceAttributes resource = new ResourceAttributes(
            trade.getDeskId(), trade.getCountryCode(), trade.getOwnerId());
    OpaClient.PointDecision decision = opaClient.evaluate(subject, resource);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("user", user);
    body.put("allow", decision.allow());
    body.put("reasons", decision.reasons());
    if (decision.allow()) body.put("trade", trade);

    return ResponseEntity.status(decision.allow() ? 200 : 403).body(body);
}
```

Fetches the trade first (404 if not found), then asks OPA whether this subject can see it. The trade's attributes are extracted into a `ResourceAttributes` object which becomes `input.resource`. On allow, the trade payload is included; on deny, only `{allow, reasons}` is returned with HTTP 403.

### `GET /trades?user=bob&from=&to=&size=&afterDate=&afterId=` — bulk SQL push-down

```java
Subject subject = roleService.resolveSubject(user);
CompiledPredicate predicate = orchestrator.compileAndAudit(subject);

if (predicate instanceof AlwaysDeny) {
    return Map.of("rows", List.of(), "appliedFilter", "<always deny>");
}

SqlPredicateRenderer.Rendered rendered = predicate instanceof Conditional cond
        ? sqlRenderer.render(cond)
        : null;  // AlwaysAllow: no extra predicate

var page = tradeQueryService.queryAllowed(from, to, rendered, size, afterDate, afterId);
return Map.of("rows", page.rows(), "appliedFilter", page.appliedSql(), "nextCursor", ...);
```

For `AlwaysDeny`, the query is never sent to the database. For `AlwaysAllow`, `rendered` is null and `TradeQueryService` runs only the date-range filter with no extra predicate. For `Conditional`, the rendered SQL fragment is AND'd into the WHERE clause.

The `appliedFilter` field in the response is the actual SQL string (with `?` placeholders, not values) so you can verify exactly what predicate ran.

### `GET /trades/es?user=bob&from=&to=&size=` — bulk ES push-down

Same flow as the SQL path but using `ElasticsearchQueryRenderer` and `ElasticTradeService`. If Elasticsearch is not running, `ElasticTradeService` throws `IllegalStateException`, which `GlobalExceptionHandler` catches and returns as a clean 502 JSON response.

### `GET /trades/_debug/compile?user=bob` — raw OPA compile response

```java
@GetMapping("/_debug/compile")
public JsonNode debugCompile(@RequestParam String user) {
    return opaClient.compileRaw(roleService.resolveSubject(user));
}
```

Returns the full, unmodified OPA Compile API response. Use this to:
- See the raw AST that `PartialEvalTranslator` processes
- Debug `UnsupportedRegoConstructException` — compare what OPA actually returned against what the translator expected
- Demonstrate the partial evaluation output directly

---

## 15. TradeQueryService — push-down query to H2/Postgres

```java
public Page queryAllowed(LocalDate from, LocalDate to,
                          SqlPredicateRenderer.Rendered predicate,
                          int size, LocalDate afterDate, Long afterId) {

    StringBuilder sql = new StringBuilder(
            "SELECT id, trade_date, desk_id, country_code, owner_id, symbol, notional " +
            "FROM trades WHERE trade_date BETWEEN ? AND ?");
    List<Object> params = new ArrayList<>(List.of(from, to));

    if (predicate != null) {
        sql.append(" AND ").append(predicate.sql());
        params.addAll(predicate.params());
    }
    if (afterDate != null && afterId != null) {
        sql.append(" AND (trade_date, id) > (?, ?)");
        params.add(afterDate);
        params.add(afterId);
    }
    sql.append(" ORDER BY trade_date, id LIMIT ?");
    params.add(size);

    List<TradeRow> rows = jdbc.query(sql.toString(), rowMapper, params.toArray());
    ...
}
```

**Query structure (for bob, page 1):**
```sql
SELECT id, trade_date, desk_id, country_code, owner_id, symbol, notional
FROM trades
WHERE trade_date BETWEEN '2024-01-01' AND '2024-12-31'
  AND (country_code IN ('US','CA','MX','BR','AR','CL','CO','PE','CR','PA'))
ORDER BY trade_date, id
LIMIT 20
```

**Keyset pagination** — `(trade_date, id) > (?, ?)` is a row value comparison. For page 2, pass `afterDate` and `afterId` from the previous page's last row. This avoids the classic `OFFSET` problem where inserting or deleting rows between pages causes rows to be skipped or duplicated. H2 2.x supports row value constructor comparisons.

**Why plain JDBC rather than JPA?** The WHERE clause fragment is an opaque string handed in by `SqlPredicateRenderer`. JPA Criteria API or Specification would add complexity without adding value here — there is no object mapping benefit when you're building a dynamic SQL fragment from an external source.

**`predicate == null` path** — `AlwaysAllow` case. The subject can see all trades in the date range; no additional filter is applied. `TradeQueryService` handles this correctly: if `predicate` is null, the `if (predicate != null)` block is skipped and the query runs with only the date range.

---

## 16. ElasticTradeService — push-down query to Elasticsearch

```java
public JsonNode search(LocalDate from, LocalDate to, Map<String, Object> compiledClause,
                        int size, LocalDate afterDate, Long afterId) {
    List<Object> filters = new ArrayList<>();
    filters.add(Map.of("range", Map.of("trade_date",
            Map.of("gte", from.toString(), "lte", to.toString()))));
    if (compiledClause != null) {
        filters.add(compiledClause);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("size", size);
    body.put("sort", List.of(Map.of("trade_date", "asc"), Map.of("id", "asc")));
    body.put("query", Map.of("bool", Map.of("filter", filters)));
    if (afterDate != null && afterId != null) {
        body.put("search_after", List.of(afterDate.toString(), afterId));
    }

    return post("/trades/_search", body);
}
```

**ES query sent for bob:**
```json
{
  "size": 20,
  "sort": [{"trade_date": "asc"}, {"id": "asc"}],
  "query": {
    "bool": {
      "filter": [
        {"range": {"trade_date": {"gte": "2024-01-01", "lte": "2024-12-31"}}},
        {"bool": {
          "should": [
            {"bool": {"filter": [{"terms": {"country_code": ["US","CA","MX",...]}}]}}
          ],
          "minimum_should_match": 1
        }}
      ]
    }
  }
}
```

The `search_after` parameter (ES's equivalent of keyset pagination) works exactly like `(trade_date, id) > (?, ?)` in SQL: pass the sort values of the last document of the previous page, ES returns the next page starting after that document.

---

## 17. GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UnsupportedRegoConstructException.class)
    public ResponseEntity<Map<String, String>> unsupportedRego(UnsupportedRegoConstructException e) {
        return ResponseEntity.status(500)
                .body(Map.of("error", e.getMessage(),
                        "hint", "compare against GET /trades/_debug/compile?user=..."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> upstreamFailure(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
```

| Exception | Thrown by | HTTP status | Meaning |
|---|---|---|---|
| `IllegalArgumentException` | `RoleService` (unknown user) | 400 | Bad request — unknown `?user=` param |
| `UnsupportedRegoConstructException` | `PartialEvalTranslator`, `SqlPredicateRenderer` | 500 | OPA returned an AST shape the translator doesn't recognize, or the policy referenced a column not in the allowlist |
| `IllegalStateException` | `OpaClient`, `ElasticTradeService` | 502 | OPA or Elasticsearch is unreachable, or returned a non-2xx response |

The 502 for `IllegalStateException` means: "I got a bad response from an upstream service". This is the correct HTTP semantics — the app itself is fine, an upstream dependency is not. The error message includes the upstream's response body, making diagnosis straightforward.

---

## 18. Seed data — `src/main/resources/data.sql`

25 trades seeded at startup via `spring.sql.init.mode=always`. Explicit column names in every INSERT avoid column-order dependency on Hibernate's DDL output.

| Trade IDs | desk_id | country_code | owner_id | Visible to |
|---|---|---|---|---|
| 1–5 | FX-NY | DE, JP, GB, CH, AE | e001–e005 | alice only (desk match) |
| 6–10 | FX-LN/HK/TK | US, CA, MX, BR, AR | e006–e010 | bob only (region match) |
| 11–17 | FX-LN/TK/SG/HK | DE, JP, SG, GB, HK, AU, NZ | p101–p107 | carol only (team match) |
| 18–20 | FX-TK/SG/HK | DE, CH, AU | e011–e013 | nobody (not FX-NY, not americas, not team-42) |
| 21 | FX-NY | US | p101 | alice+bob+carol (all three rules match) |
| 22 | FX-NY | CA | p108 | alice+bob+carol |
| 23 | FX-NY | DE | p106 | alice+carol (desk+team, not americas) |
| 24 | FX-SG | US | p109 | bob+carol (region+team, not FX-NY) |
| 25 | FX-LN | CO | e014 | bob only (CO = Colombia, in americas) |

Trades 18–20 are intentional non-matches — they demonstrate that the deny path works and no trade is visible to everyone by default.

---

## 19. OPA config and decision logging — `opa-config.yaml`

```yaml
decision_logs:
  console: true
  reporting:
    min_delay_seconds: 5
    max_delay_seconds: 10
```

OPA's decision log plugin captures every call to `POST /v1/data/...` (full evaluation). It does **not** capture calls to `POST /v1/compile` (partial evaluation) — that is why `PermissionOrchestrator` emits its own audit log.

**A full evaluation decision log entry:**
```json
{
  "decision_id": "6330c3d6-...",
  "timestamp": "2026-11-07T18:12:08Z",
  "type": "openpolicyagent.org/decision_logs",
  "path": "trades/authz",
  "input": {
    "subject": {"username": "alice", "role": "banker", "desk_id": "FX-NY"},
    "resource": {"desk_id": "FX-NY", "country_code": "DE", "owner_id": "e001"}
  },
  "result": {"allow": true, "reasons": ["desk_match"]},
  "requested_by": "[::1]:56391",
  "metrics": {
    "timer_rego_query_eval_ns": 60500,
    "timer_server_handler_ns": 308583
  }
}
```

**Shipping to a remote system** — uncomment the `plugin: http` block in `opa-config.yaml`:

```yaml
decision_logs:
  console: true
  plugin: http
  service: compliance_backend

services:
  compliance_backend:
    url: https://your-splunk-hec-or-es-endpoint
    credentials:
      bearer:
        token: "${COMPLIANCE_TOKEN}"
```

OPA batches entries and POSTs them as a JSON array every 5–10 seconds. No log shipper middleware required.

---

## 20. Test suite

### Rego policy tests — `policies/trades/authz_test.rego`

Run with:
```bash
opa test ./policies -v
```

8 tests covering all three rules, the deny-by-default case, an unknown role, and the `reasons` set:

| Test | What it verifies |
|---|---|
| `test_banker_sees_own_desk` | Rule 1: same desk → allow |
| `test_banker_does_not_see_other_desk` | Rule 1: different desk → deny |
| `test_region_head_sees_country_in_region` | Rule 2: country in region → allow |
| `test_region_head_does_not_see_country_outside_region` | Rule 2: country outside region → deny |
| `test_business_manager_sees_team_members_trades` | Rule 3: owner on team → allow |
| `test_business_manager_does_not_see_non_team_trade` | Rule 3: owner not on team → deny |
| `test_unknown_role_denied_by_default` | default deny |
| `test_reasons_explain_which_rule_matched` | `reasons` set contains the right label |

These tests run without a server — OPA evaluates the policy in-process. They are fast (milliseconds) and have no external dependencies.

### Java unit tests — `PartialEvalTranslatorTest.java`

Run with:
```bash
cd app && mvn test
```

6 tests covering the translator and both renderers:

| Test | Input shape | What it verifies |
|---|---|---|
| `eqClauseTranslatesToSqlEquality` | `eq` operator, resource ref left | `desk_id = ?` with correct param |
| `inClauseTranslatesToSqlInListAndEsTermsQuery` | `internal.member_2`, array right | SQL `IN (?,?,?)` and ES `terms` clause |
| `emptyOrBranchMeansAlwaysAllow` | `[[]]` (one empty branch) | `AlwaysAllow` returned |
| `missingQueriesMeansAlwaysDeny` | `null` | `AlwaysDeny` returned |
| `twoOrBranchesJoinWithOr` | Two branches, different operators | SQL contains `) OR (`, correct param count |
| `unsupportedOperatorThrowsInsteadOfSilentlyMistranslating` | `abs` operator | `UnsupportedRegoConstructException` thrown |

The fixtures are hand-built JSON strings that match OPA's documented AST format exactly. These tests verify the translation algorithm independently of a live OPA server.

---

## 21. End-to-end worked example

**Request:** `GET /trades?user=bob&from=2024-01-01&to=2024-12-31&size=5`

**Step 1 — Controller receives request**
`TradeController.list()` is called. `user="bob"`, `from=2024-01-01`, `to=2024-12-31`, `size=5`.

**Step 2 — RoleService resolves subject**
`RoleService.resolveSubject("bob")` → `Subject.regionHead("bob", "americas")`.
Serializes (via Jackson SNAKE_CASE) to:
```json
{"username": "bob", "role": "region_head", "region": "americas"}
```

**Step 3 — OpaClient sends compile request**
`PermissionOrchestrator` calls `OpaClient.compileQueries(subject)`.
Body sent to `POST http://localhost:8181/v1/compile`:
```json
{
  "query": "data.trades.authz.allow == true",
  "input": {"subject": {"username": "bob", "role": "region_head", "region": "americas"}},
  "unknowns": ["input.resource"]
}
```

**Step 4 — OPA partially evaluates the policy**
OPA evaluates `data.trades.authz.allow == true`:
- Rule 1 (`banker`): `input.subject.role == "banker"` → `"region_head" == "banker"` → false. This branch is eliminated.
- Rule 2 (`region_head`): `input.subject.role == "region_head"` → true. `data.regions["americas"]` → `["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]`. `input.resource.country_code` is unknown. Residual: `input.resource.country_code in ["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]`.
- Rule 3 (`business_manager`): `input.subject.role == "business_manager"` → false. This branch is eliminated.

OPA returns:
```json
{
  "result": {
    "queries": [[
      {
        "terms": [
          {"type":"ref","value":[{"type":"var","value":"internal"},{"type":"string","value":"member_2"}]},
          {"type":"ref","value":[{"type":"var","value":"input"},{"type":"string","value":"resource"},{"type":"string","value":"country_code"}]},
          {"type":"array","value":[{"type":"string","value":"US"},{"type":"string","value":"CA"},...]}
        ]
      }
    ]]
  }
}
```

**Step 5 — PartialEvalTranslator walks the AST**
- `queriesNode` is a non-empty array → not AlwaysDeny
- Outer loop: one branch
- Inner loop: one expression
  - `terms[0]` → `refToDottedName` → `"internal.member_2"` → `isMember()` = true → IN condition
  - `terms[1]` → ref path → `"input.resource.country_code"` → field = `"country_code"`
  - `terms[2]` → array → values = `["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"]`
  - produces `Condition("country_code", IN, ["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"])`
- Returns `Conditional([[Condition("country_code", IN, [...])]])`

**Step 6 — PermissionOrchestrator emits audit log**
```json
{
  "decision_id": "56f3c467-...",
  "timestamp": "2026-11-07T18:19:42Z",
  "type": "openpolicyagent.org/partial_eval",
  "path": "trades/authz",
  "subject": {"username": "bob", "role": "region_head", "region": "americas"},
  "result": {
    "outcome": "conditional",
    "filter": "country_code IN [US, CA, MX, BR, AR, CL, CO, PE, CR, PA]",
    "branch_count": 1
  }
}
```

**Step 7 — SqlPredicateRenderer renders SQL fragment**
`Rendered("(country_code IN (?,?,?,?,?,?,?,?,?,?))", ["US","CA","MX","BR","AR","CL","CO","PE","CR","PA"])`

**Step 8 — TradeQueryService builds and executes the query**
Final SQL (with `?` placeholders shown as values for readability):
```sql
SELECT id, trade_date, desk_id, country_code, owner_id, symbol, notional
FROM trades
WHERE trade_date BETWEEN '2024-01-01' AND '2024-12-31'
  AND (country_code IN ('US','CA','MX','BR','AR','CL','CO','PE','CR','PA'))
ORDER BY trade_date, id
LIMIT 5
```
Rows returned: trades 6, 7, 8, 9, 10, 21, 22, 24, 25 (all have americas country codes).

**Step 9 — HTTP response**
```json
{
  "user": "bob",
  "rows": [ ... 5 trade rows ... ],
  "appliedFilter": "SELECT id, trade_date, ... WHERE trade_date BETWEEN ? AND ? AND (country_code IN (?,?,?,?,?,?,?,?,?,?)) ORDER BY trade_date, id LIMIT ?",
  "nextCursor": {"afterDate": "2024-02-20", "afterId": 8}
}
```
