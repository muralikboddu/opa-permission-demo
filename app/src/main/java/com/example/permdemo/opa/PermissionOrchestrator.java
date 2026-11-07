package com.example.permdemo.opa;

import com.example.permdemo.model.Subject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wraps the compile → translate → audit-log pipeline for bulk/push-down requests.
 *
 * Every call emits one structured JSON log entry (type "partial_eval") in the
 * same shape as OPA's own decision log entries, so both streams can be shipped
 * to the same compliance sink without any downstream schema gymnastics.
 *
 * Log entry fields:
 *   decision_id  - UUID, correlate with downstream query logs
 *   timestamp    - UTC ISO-8601
 *   type         - "openpolicyagent.org/partial_eval"
 *   path         - policy rule compiled against
 *   subject      - who triggered the bulk request (role, desk/region/team)
 *   result
 *     outcome    - always_allow | always_deny | conditional
 *     filter     - human-readable residual pushed into the query layer
 *     branch_count - number of OR branches in the residual
 */
@Component
public class PermissionOrchestrator {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit.partial_eval");

    private final OpaClient opaClient;
    private final PartialEvalTranslator translator;
    private final ObjectMapper mapper;

    public PermissionOrchestrator(OpaClient opaClient, PartialEvalTranslator translator, ObjectMapper mapper) {
        this.opaClient = opaClient;
        this.translator = translator;
        this.mapper = mapper;
    }

    public CompiledPredicate compileAndAudit(Subject subject) {
        String decisionId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();

        CompiledPredicate predicate = translator.translate(opaClient.compileQueries(subject));

        emitAuditLog(decisionId, timestamp, subject, predicate);
        return predicate;
    }

    private void emitAuditLog(String decisionId, Instant timestamp, Subject subject, CompiledPredicate predicate) {
        try {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("decision_id", decisionId);
            entry.put("timestamp", timestamp.toString());
            entry.put("type", "openpolicyagent.org/partial_eval");
            entry.put("path", "trades/authz");
            entry.set("subject", mapper.valueToTree(subject));

            ObjectNode result = entry.putObject("result");
            switch (predicate) {
                case CompiledPredicate.AlwaysAllow ignored -> {
                    result.put("outcome", "always_allow");
                    result.put("filter", "ALLOW_ALL — no filter applied, full dataset visible");
                    result.put("branch_count", 0);
                }
                case CompiledPredicate.AlwaysDeny ignored -> {
                    result.put("outcome", "always_deny");
                    result.put("filter", "DENY_ALL — query suppressed, no rows returned");
                    result.put("branch_count", 0);
                }
                case CompiledPredicate.Conditional cond -> {
                    result.put("outcome", "conditional");
                    result.put("filter", describeConditional(cond));
                    result.put("branch_count", cond.orOfAndConditions().size());
                }
            }

            AUDIT.info(mapper.writeValueAsString(entry));
        } catch (Exception e) {
            AUDIT.warn("Failed to emit partial-eval audit log for decision {}: {}", decisionId, e.getMessage());
        }
    }

    private String describeConditional(CompiledPredicate.Conditional cond) {
        return cond.orOfAndConditions().stream()
                .map(branch -> branch.stream()
                        .map(c -> switch (c.op()) {
                            case IN     -> c.field() + " IN " + c.values();
                            case NOT_IN -> c.field() + " NOT IN " + c.values();
                            case EQ     -> c.field() + " = " + c.value();
                            case NEQ    -> c.field() + " != " + c.value();
                            case LT     -> c.field() + " < " + c.value();
                            case LTE    -> c.field() + " <= " + c.value();
                            case GT     -> c.field() + " > " + c.value();
                            case GTE    -> c.field() + " >= " + c.value();
                        })
                        .collect(Collectors.joining(" AND ")))
                .collect(Collectors.joining(" OR "));
    }
}
