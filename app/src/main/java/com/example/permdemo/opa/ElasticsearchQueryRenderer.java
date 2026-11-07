package com.example.permdemo.opa;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.permdemo.opa.CompiledPredicate.Condition;
import static com.example.permdemo.opa.CompiledPredicate.Conditional;

/**
 * Renders the same CompiledPredicate.Conditional the SQL path uses into
 * Elasticsearch Query DSL: each OR-branch becomes one `should` clause (any
 * one matching is enough), each AND-branch becomes a `bool.filter` list
 * (all must match), and `!=` / `not in` become `bool.must_not`.
 *
 * This is the same idea as the open-source data_filter_elasticsearch
 * contrib module discussed earlier, scoped down to exactly the operators
 * our demo policy uses (== and in).
 */
@Component
public class ElasticsearchQueryRenderer {

    public Map<String, Object> render(Conditional predicate) {
        List<Object> should = new ArrayList<>();
        for (List<Condition> and : predicate.orOfAndConditions()) {
            should.add(renderAnd(and));
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("should", should);
        bool.put("minimum_should_match", 1);
        return Map.of("bool", bool);
    }

    private Map<String, Object> renderAnd(List<Condition> and) {
        List<Object> filter = new ArrayList<>();
        List<Object> mustNot = new ArrayList<>();

        for (Condition c : and) {
            switch (c.op()) {
                case EQ -> filter.add(Map.of("term", Map.of(c.field(), c.value())));
                case NEQ -> mustNot.add(Map.of("term", Map.of(c.field(), c.value())));
                case IN -> filter.add(Map.of("terms", Map.of(c.field(), c.values())));
                case NOT_IN -> mustNot.add(Map.of("terms", Map.of(c.field(), c.values())));
                case LT -> filter.add(Map.of("range", Map.of(c.field(), Map.of("lt", c.value()))));
                case LTE -> filter.add(Map.of("range", Map.of(c.field(), Map.of("lte", c.value()))));
                case GT -> filter.add(Map.of("range", Map.of(c.field(), Map.of("gt", c.value()))));
                case GTE -> filter.add(Map.of("range", Map.of(c.field(), Map.of("gte", c.value()))));
            }
        }

        Map<String, Object> boolBody = new LinkedHashMap<>();
        boolBody.put("filter", filter);
        if (!mustNot.isEmpty()) {
            boolBody.put("must_not", mustNot);
        }
        return Map.of("bool", boolBody);
    }
}
