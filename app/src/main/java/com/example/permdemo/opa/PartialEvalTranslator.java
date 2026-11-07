package com.example.permdemo.opa;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.example.permdemo.opa.CompiledPredicate.*;

/**
 * Walks the `result.queries` array from OPA's Compile API response and
 * turns it into a small, engine-agnostic CompiledPredicate -- which
 * SqlTranslator / ElasticsearchTranslator then render into a real WHERE
 * clause or Query DSL fragment.
 *
 * This only understands the constrained "linear fragment" of Rego that
 * OPA's own docs describe as translatable into a target query (see
 * https://www.openpolicyagent.org/docs/filtering/fragment): plain equality
 * / comparison operators against one of our resource fields, and the `in`
 * membership operator. That's deliberate -- it's exactly the subset our
 * authz.rego policy uses, and matches the constraints OPA imposes on any
 * policy you want to push down, regardless of language.
 *
 * Expression shape handled (OPA's stable ast.Expr / ast.Term JSON form):
 *   { "terms": [ <operator ref>, <operand>, <operand> ], "negated": bool? }
 * where <operator ref> is a ref term naming a builtin ("eq", "neq", "lt",
 * "lte", "gt", "gte") or the qualified "internal.member_2" builtin that
 * `x in y` desugars to, and each <operand> is either:
 *   - a ref into input.resource.<field>  (our "unknown")
 *   - a literal (string/number/boolean)
 *   - an array of literals (the right-hand side of `in`)
 *
 * See UnsupportedRegoConstructException's Javadoc for why this needed to
 * be built from documented shape rather than verified against a live OPA
 * server, and what to do if the real output differs.
 */
@Component
public class PartialEvalTranslator {

    private static final String RESOURCE_PREFIX = "input.resource.";

    public CompiledPredicate translate(JsonNode queriesNode) {
        if (queriesNode == null || !queriesNode.isArray() || queriesNode.isEmpty()) {
            return new AlwaysDeny();
        }

        List<List<Condition>> orBranches = new ArrayList<>();
        for (JsonNode branch : queriesNode) {
            if (!branch.isArray()) {
                throw new UnsupportedRegoConstructException("expected an array of expressions per branch", branch);
            }
            if (branch.isEmpty()) {
                // One OR-branch with no remaining conditions -> unconditionally
                // true for this subject. Since it's OR'd with everything else,
                // the whole predicate collapses to "allow everything".
                return new AlwaysAllow();
            }
            List<Condition> andConditions = new ArrayList<>();
            for (JsonNode expr : branch) {
                andConditions.add(translateExpr(expr));
            }
            orBranches.add(andConditions);
        }
        return new Conditional(orBranches);
    }

    private Condition translateExpr(JsonNode expr) {
        JsonNode terms = expr.get("terms");
        if (terms == null || !terms.isArray() || terms.size() != 3) {
            throw new UnsupportedRegoConstructException(
                    "only binary operator expressions (terms.length == 3) are supported", expr);
        }
        boolean negated = expr.path("negated").asBoolean(false);

        String opName = refToDottedName(terms.get(0));
        JsonNode left = terms.get(1);
        JsonNode right = terms.get(2);

        if (isMember(opName)) {
            String field = resourceField(left);
            List<String> values = literalArray(right);
            Op op = negated ? Op.NOT_IN : Op.IN;
            return new Condition(field, op, values);
        }

        Op baseOp = comparisonOp(opName);
        // The unknown ref can legally appear on either side; normalize so
        // `field` is always the resource column and `literal` the constant.
        String field;
        String literal;
        if (isResourceRef(left)) {
            field = resourceField(left);
            literal = literalValue(right);
        } else if (isResourceRef(right)) {
            field = resourceField(right);
            literal = literalValue(left);
            baseOp = flip(baseOp);
        } else {
            throw new UnsupportedRegoConstructException(
                    "expected exactly one side to reference input.resource.*", expr);
        }
        Op op = negated ? negate(baseOp) : baseOp;
        return new Condition(field, op, List.of(literal));
    }

    private boolean isMember(String opName) {
        return opName.endsWith("member_2") || opName.endsWith("member_3");
    }

    private Op comparisonOp(String opName) {
        return switch (opName) {
            case "eq", "equal" -> Op.EQ;
            case "neq", "ne" -> Op.NEQ;
            case "lt" -> Op.LT;
            case "lte", "le" -> Op.LTE;
            case "gt" -> Op.GT;
            case "gte", "ge" -> Op.GTE;
            default -> throw new UnsupportedRegoConstructException(
                    "unsupported operator '" + opName + "' -- extend PartialEvalTranslator to add it", opName);
        };
    }

    private Op flip(Op op) {
        return switch (op) {
            case LT -> Op.GT;
            case LTE -> Op.GTE;
            case GT -> Op.LT;
            case GTE -> Op.LTE;
            default -> op; // EQ/NEQ are symmetric
        };
    }

    private Op negate(Op op) {
        return switch (op) {
            case EQ -> Op.NEQ;
            case NEQ -> Op.EQ;
            default -> throw new UnsupportedRegoConstructException(
                    "negation of comparison operator " + op + " is not supported in this demo", op);
        };
    }

    private boolean isResourceRef(JsonNode term) {
        if (!"ref".equals(term.path("type").asText())) {
            return false;
        }
        try {
            return refToDottedName(term).startsWith(RESOURCE_PREFIX);
        } catch (UnsupportedRegoConstructException e) {
            return false;
        }
    }

    private String resourceField(JsonNode refTerm) {
        String dotted = refToDottedName(refTerm);
        if (!dotted.startsWith(RESOURCE_PREFIX)) {
            throw new UnsupportedRegoConstructException(
                    "expected a reference under " + RESOURCE_PREFIX, refTerm);
        }
        return dotted.substring(RESOURCE_PREFIX.length());
    }

    /**
     * Turns a `ref` term (or a bare `var` term) into a dotted path, e.g.
     * the ref [var(input), string(resource), string(desk_id)] becomes
     * "input.resource.desk_id", and the qualified builtin ref
     * [var(internal), string(member_2)] becomes "internal.member_2".
     */
    private String refToDottedName(JsonNode term) {
        String type = term.path("type").asText();
        if ("var".equals(type)) {
            return term.path("value").asText();
        }
        if (!"ref".equals(type)) {
            throw new UnsupportedRegoConstructException("expected a ref or var term", term);
        }
        JsonNode parts = term.get("value");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw new UnsupportedRegoConstructException("ref term had no segments", term);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String partType = part.path("type").asText();
            String text = switch (partType) {
                case "var", "string" -> part.path("value").asText();
                default -> throw new UnsupportedRegoConstructException(
                        "unsupported ref segment type '" + partType + "'", part);
            };
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private String literalValue(JsonNode term) {
        String type = term.path("type").asText();
        JsonNode value = term.get("value");
        if (value == null) {
            throw new UnsupportedRegoConstructException("literal term had no value", term);
        }
        return switch (type) {
            case "string", "number", "boolean" -> value.asText();
            default -> throw new UnsupportedRegoConstructException(
                    "unsupported literal type '" + type + "'", term);
        };
    }

    private List<String> literalArray(JsonNode arrayTerm) {
        if (!"array".equals(arrayTerm.path("type").asText())) {
            throw new UnsupportedRegoConstructException("expected an array literal on the right of `in`", arrayTerm);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : arrayTerm.get("value")) {
            values.add(literalValue(item));
        }
        return values;
    }
}
