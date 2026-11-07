package com.example.permdemo.opa;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.permdemo.opa.CompiledPredicate.Condition;
import static com.example.permdemo.opa.CompiledPredicate.Conditional;

/**
 * Renders a CompiledPredicate.Conditional into a parameterized SQL
 * fragment: "(colA = ? AND colB IN (?,?)) OR (colC = ?)" plus the ordered
 * parameter list to bind with a PreparedStatement / JdbcTemplate. This is
 * the hand-rolled equivalent of what OPA's own SQL-targeted Compile API
 * (or the community opa-data-filter-spring-boot-starter project) would
 * hand you directly if you were on a build that supports it -- see the
 * README for that distinction.
 */
@Component
public class SqlPredicateRenderer {

    // Only columns the trades table actually has. Never interpolate a
    // field name from the policy engine straight into SQL text without
    // checking it against something like this -- the whole point of
    // parameterizing values with `?` is defeated if the column name
    // itself is attacker- or bug-controlled.
    private static final Set<String> ALLOWED_COLUMNS = Set.of("desk_id", "country_code", "owner_id");

    public record Rendered(String sql, List<Object> params) {}

    public Rendered render(Conditional predicate) {
        List<Object> params = new ArrayList<>();
        String sql = predicate.orOfAndConditions().stream()
                .map(and -> renderAnd(and, params))
                .collect(Collectors.joining(" OR ", "(", ")"));
        return new Rendered(sql, params);
    }

    private String renderAnd(List<Condition> and, List<Object> params) {
        return and.stream()
                .map(c -> renderCondition(c, params))
                .collect(Collectors.joining(" AND ", "(", ")"));
    }

    private String renderCondition(Condition c, List<Object> params) {
        String col = column(c.field());
        return switch (c.op()) {
            case EQ -> {
                params.add(c.value());
                yield col + " = ?";
            }
            case NEQ -> {
                params.add(c.value());
                yield col + " <> ?";
            }
            case LT -> {
                params.add(c.value());
                yield col + " < ?";
            }
            case LTE -> {
                params.add(c.value());
                yield col + " <= ?";
            }
            case GT -> {
                params.add(c.value());
                yield col + " > ?";
            }
            case GTE -> {
                params.add(c.value());
                yield col + " >= ?";
            }
            case IN -> {
                params.addAll(c.values());
                yield col + " IN (" + placeholders(c.values().size()) + ")";
            }
            case NOT_IN -> {
                params.addAll(c.values());
                yield col + " NOT IN (" + placeholders(c.values().size()) + ")";
            }
        };
    }

    private String column(String field) {
        if (!ALLOWED_COLUMNS.contains(field)) {
            throw new UnsupportedRegoConstructException(
                    "policy referenced a field that isn't an allow-listed trades column", field);
        }
        return field;
    }

    private String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
