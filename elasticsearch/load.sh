#!/bin/sh
# Runs inside the curlimages/curl "es-seed" container after Elasticsearch is
# healthy: creates the trades index with an explicit mapping, then bulk-loads
# the pre-generated NDJSON sample so the ES push-down scenario has something
# to query. This is a smaller sample (20k docs) than the 1M-row Postgres
# table -- big enough to see filtering work, small enough to load instantly
# on a laptop without a dedicated bulk-indexing pipeline.

set -e

ES_URL="${ELASTICSEARCH_URL:-http://elasticsearch:9200}"

echo "Waiting for Elasticsearch at $ES_URL ..."
until curl -s -o /dev/null "$ES_URL/_cluster/health"; do
  sleep 2
done

echo "Creating trades index with mapping ..."
curl -s -X PUT "$ES_URL/trades" \
  -H 'Content-Type: application/json' \
  --data-binary @/seed/mapping.json

echo "Bulk-loading seed documents ..."
curl -s -X POST "$ES_URL/_bulk?refresh=true" \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @/seed/seed_bulk.ndjson \
  | tail -c 500

echo ""
echo "Done. Doc count:"
curl -s "$ES_URL/trades/_count"
echo ""
