#!/usr/bin/env bash
# Checks the production profile's observability (ADR-022) on a running stack, after the browser
# tests have sent traffic through it:
#   - every service serves metrics on the internal network;
#   - the public entry points expose neither metrics nor a caller-chosen trace;
#   - log lines are JSON and carry trace IDs.
# Usage: COMPOSE="docker compose -p servora-prod -f docker-compose.yml -f compose.prod.yml" \
#          bash infrastructure/ci/check-observability.sh
set -euo pipefail

: "${COMPOSE:?Set COMPOSE to the docker compose command for the running stack}"
WEB="${SERVORA_CHECK_WEB:-http://127.0.0.1:8000}"
WIDGET="${SERVORA_CHECK_WIDGET:-http://127.0.0.1:8001}"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Output is captured before searching it: `grep -q` stops reading early, which would make the
# producing command fail under pipefail.
contains() {
  local text=$1 pattern=$2
  grep -Eq -- "$pattern" <<<"$text"
}

# Fetches a URL from inside the internal network (the AI service container has Python).
internal_get() {
  $COMPOSE exec -T ai-service python -c "
import sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=10) as response:
    sys.stdout.write(response.read().decode())
" "$1"
}

echo "Metrics on the internal network"
core=$(internal_get http://core-api:8081/actuator/prometheus)
contains "$core" '^http_server_requests_seconds_count' || fail "core API metrics missing http_server_requests"
contains "$core" '^servora_outbox_oldest_pending_age_seconds' || fail "core API metrics missing the outbox gauge"
contains "$core" '^servora_ai_answers_seconds_count' || fail "core API metrics missing AI answers"
ai=$(internal_get http://ai-service:8091/metrics)
contains "$ai" '^servora_ai_answers_total' || fail "AI service metrics missing servora_ai_answers_total"
worker_api=$(internal_get http://worker-api:8090/metrics)
contains "$worker_api" '^servora_worker_queue_depth' || fail "worker API metrics missing the queue depth"
worker=$(internal_get http://document-worker:9100/metrics)
contains "$worker" '^servora_worker_documents_total' || fail "document worker metrics missing documents processed"

echo "Nothing observability-related on the public entry points"
for base in "$WEB" "$WIDGET"; do
  for path in /actuator/prometheus /actuator/health /metrics; do
    body=$(curl -s "$base$path" || true)
    if contains "$body" '^# (HELP|TYPE) '; then
      fail "$base$path serves metrics"
    fi
  done
done

echo "The gateway starts traces itself"
forged=4bf92f3577b34da6a3ce929d0e0e4736
headers=$(curl -s -o /dev/null -D - -H "traceparent: 00-$forged-00f067aa0ba902b7-01" "$WEB/api/v1/workspaces")
request_id=$(tr -d '\r' <<<"$headers" | awk -F': ' 'tolower($1) == "x-request-id" { print $2 }')
[[ "$request_id" =~ ^[0-9a-f]{32}$ ]] || fail "X-Request-Id is not a trace ID: '$request_id'"
[[ "$request_id" != "$forged" ]] || fail "the gateway let a client choose the trace ID"

echo "JSON log lines with trace IDs"
check_json_logs() {
  local service=$1 pattern=$2 logs
  logs=$($COMPOSE logs --no-log-prefix --no-color "$service" 2>/dev/null)
  contains "$logs" "^\{.*$pattern" || fail "$service has no JSON log line with a trace ID"
}
check_json_logs core-api '"traceId":"[0-9a-f]{32}"'
check_json_logs ai-service '"trace_id": "[0-9a-f]{32}"'
check_json_logs worker-api '"trace_id": "[0-9a-f]{32}"'
check_json_logs document-worker '"trace_id": "[0-9a-f]{32}"'

echo "Observability checks passed"
