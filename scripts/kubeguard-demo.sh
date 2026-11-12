#!/usr/bin/env bash
# Helper for the KubeGuard live demo. Uses $KUBECONFIG (or the default kubeconfig). KubeGuard, Alertmanager and
# the responder run in namespace kubeguard (no chaos); the mock chaos targets in kubeguard-demo.
#
#   scripts/kubeguard-demo.sh status                 # KubeGuard incidents + Alertmanager alerts
#   scripts/kubeguard-demo.sh inject latency         # scenario 1: slow pricing stub (mock stream)
#   scripts/kubeguard-demo.sh inject connectivity    # scenario 2: inventory stub unreachable (mock stream)
#   scripts/kubeguard-demo.sh inject db-latency      # scenario 3: slow PostgreSQL for the real backend
#   scripts/kubeguard-demo.sh inject db-connectivity # scenario 4: PostgreSQL unreachable for the real backend
#   scripts/kubeguard-demo.sh inject p95             # scenario 5: mock-backend itself slow (p95 high), downstreams fine
#   scripts/kubeguard-demo.sh recover                # remove all KubeGuard chaos experiments
#   scripts/kubeguard-demo.sh watch [seconds]        # print status every 5s
#   scripts/kubeguard-demo.sh notifications          # webhook notifications Alertmanager sent (firing/resolved)
#   scripts/kubeguard-demo.sh run <scenario>         # full scenario with checks and timings (exit 1 on failure)
#   scripts/kubeguard-demo.sh respond pod-db-latency # responder test: kagent + restart must fix it (FIXED)
#   scripts/kubeguard-demo.sh respond db-latency     # responder test: not fixable here, must escalate (ESCALATED)
#   scripts/kubeguard-demo.sh respond bad-deploy     # responder test: crash-looping new revision -> rollback (FIXED)
#   scripts/kubeguard-demo.sh respond container-kill # responder test: containers killed again and again (ESCALATED)
#   scripts/kubeguard-demo.sh responder              # incidents the responder handled, with timelines
#   scripts/kubeguard-demo.sh heap                   # memory-leak scenario: every link from the backend's heap to the forecast
set -euo pipefail

NS=kubeguard
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXPERIMENTS="$ROOT/chaos-mesh/experiments/kubeguard"
k() { kubectl -n "$NS" "$@"; }
# HTTP GET on a service through the API server proxy: a plain request with a timeout. (kubectl exec
# streams can hang for minutes on a busy control plane, --request-timeout does not cover them.)
api() { kubectl --request-timeout=10s get --raw "/api/v1/namespaces/$NS/services/$1/proxy$2"; }

incidents() {
  api kubeguard:8080 /api/incidents | python3 -c '
import json, sys
for i in json.load(sys.stdin):
    e = i["lastEvaluation"] or {}
    print("  %-9s %-24s %s -> %-10s %s" % (i["state"], i["rule"], i["service"], i["dependency"], e.get("explanation", "")))'
}

alerts() {
  api alertmanager:9093 "/api/v2/alerts?active=true" | python3 -c '
import json, sys
alerts = json.load(sys.stdin)
for a in alerts:
    l = a["labels"]
    print("  %-40s %s -> %s  since %s" % (l["alertname"], l.get("service"), l.get("dependency"), a["startsAt"]))
if not alerts:
    print("  (none)")' || true
}

status() {
  echo "== $(date +%T) KubeGuard incidents"
  incidents
  echo "== Alertmanager active alerts"
  alerts
  echo "== Chaos experiments"
  kubectl get networkchaos,iochaos -A -l app.kubernetes.io/part-of=kubeguard-demo --no-headers \
    -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name,ACTION:.spec.action,SINCE:.metadata.creationTimestamp \
    2>/dev/null | sed 's/^/  /' || true
}

state_of() { # rule dependency
  api kubeguard:8080 /api/incidents | python3 -c '
import json, sys
rule, dep = sys.argv[1], sys.argv[2]
print(next((i["state"] for i in json.load(sys.stdin) if i["rule"] == rule and i["dependency"] == dep), "UNKNOWN"))' "$1" "$2"
}

samples_of() { # rule dependency -> samples in the current window
  api kubeguard:8080 /api/incidents | python3 -c '
import json, sys
rule, dep = sys.argv[1], sys.argv[2]
print(next(((i["lastEvaluation"] or {}).get("sampleCount", 0) for i in json.load(sys.stdin)
            if i["rule"] == rule and i["dependency"] == dep), 0))' "$1" "$2"
}

am_active() { # alertname dependency -> number of active alerts
  local filter
  filter=$(python3 -c 'import sys, urllib.parse as u; print("&".join("filter=" + u.quote(f"{k}=\"{v}\"", safe="") for k, v in zip(("alertname", "dependency"), sys.argv[1:])))' "$1" "$2")
  api alertmanager:9093 "/api/v2/alerts?active=true&$filter" |
    python3 -c 'import json, sys; print(len(json.load(sys.stdin)))'
}

wait_for() { # description timeout-seconds command...
  local what=$1 limit=$2; shift 2
  local start=$SECONDS
  until "$@" >/dev/null 2>&1; do
    if [ $((SECONDS - start)) -ge "$limit" ]; then echo "FAILED: $what not reached within ${limit}s" >&2; return 1; fi
    sleep 2
  done
  printf '  %-45s +%ss\n' "$what" $((SECONDS - t0))
}

responder_runs() { # pretty-print the responder's incidents (last $1, default all)
  api kubeguard-responder:8080 /api/incidents | python3 -c '
import json, sys
runs = json.load(sys.stdin)[-int(sys.argv[1]):]
for r in runs:
    print("  %s  decision: %s  issue: %s" % (r["phase"], r.get("decision") or "-", r.get("issueUrl") or "-"))
    for t in r["timeline"]:
        print("    %s %s" % (t[11:19], t[31:260]))' "${1:-1000}"
}

# The bad-deploy scenario: start sets an invalid Quarkus duration on the backend Deployment (new revision,
# crash loop); stop removes it again if the responder has not rolled it back already.
bad_deploy() {
  case "$1" in
    start) kubectl -n my-namespace set env deployment/my-app "QUARKUS_HTTP_IDLE_TIMEOUT=30 seconds" ;;
    stop) kubectl -n my-namespace set env deployment/my-app QUARKUS_HTTP_IDLE_TIMEOUT- >/dev/null 2>&1 || true ;;
  esac
}

responder_count() { api kubeguard-responder:8080 /api/incidents | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))'; }

responder_last_phase() {
  api kubeguard-responder:8080 /api/incidents | python3 -c '
import json, sys
runs = json.load(sys.stdin)
print(runs[-1]["phase"] if len(runs) > int(sys.argv[1]) else "NONE")' "$1"
}

# Responder scenario: inject, wait for the responder's outcome, compare with the expected phase.
# The chaos stays in place: for a fixable scenario the action has to fix it, not the clean-up.
run_respond() {
  local rule dep alert experiment expect before phase
  scenario "$1"
  case "$1" in pod-db-latency|bad-deploy) expect=FIXED ;; *) expect=ESCALATED ;; esac
  echo "== responder scenario $1 (${experiment:-new revision with an invalid setting}), expected outcome $expect"
  local need=10
  [ "$rule" = pod-crashloop ] && need=1
  [ "$(samples_of "$rule" "$dep")" -ge "$need" ] || { echo "FAILED: KubeGuard has no current $rule data for the backend" >&2; exit 1; }
  before=$(responder_count)
  if [ -n "$experiment" ]; then
    # shellcheck disable=SC2064 # expand now: $experiment is local and gone when the trap runs
    trap "kubectl delete -f '$EXPERIMENTS/$experiment' --ignore-not-found --wait=false >/dev/null 2>&1" EXIT
  else
    trap "bad_deploy stop" EXIT
  fi
  t0=$SECONDS
  if [ -n "$experiment" ]; then kubectl apply -f "$EXPERIMENTS/$experiment" >/dev/null; else bad_deploy start >/dev/null; fi
  echo "  fault injected"
  local limit=$((SECONDS + 420))
  while :; do
    phase=$(responder_last_phase "$before" 2>/dev/null || echo NONE)
    case "$phase" in FIXED|ESCALATED|RESOLVED_BEFORE_ACTION) break ;; esac
    [ $SECONDS -lt $limit ] || { echo "FAILED: no responder outcome within 7 minutes (last: $phase)" >&2; exit 1; }
    sleep 3
  done
  echo "  responder outcome $phase after $((SECONDS - t0))s"
  responder_runs 1
  [ "$phase" = "$expect" ] || { echo "== responder scenario $1 FAILED: expected $expect" >&2; exit 1; }
  echo "== responder scenario $1 PASSED"
}

# Scenario -> rule, dependency, alertname and Chaos Mesh experiment. $also: incident keys ("rule dependency") that
# also fire as a symptom, e.g. the endpoint that waits for a slow dependency.
scenario() {
  also=""
  case "$1" in
    latency) rule=downstream-latency; dep=pricing; alert=KubeGuardDownstreamLatencyHigh
      experiment=downstream-latency.yaml; also="service-latency GET /api/quote" ;;
    connectivity) rule=dependency-connectivity; dep=inventory; alert=KubeGuardDependencyConnectivityFailure
      experiment=dependency-connectivity.yaml ;;
    db-latency) rule=downstream-latency; dep=my-app-postgresql; alert=KubeGuardDownstreamLatencyHigh
      experiment=backend-db-latency.yaml; also="service-latency POST /api/echo" ;;
    db-connectivity) rule=dependency-connectivity; dep=my-app-postgresql
      alert=KubeGuardDependencyConnectivityFailure; experiment=backend-db-connectivity.yaml ;;
    pod-db-latency) rule=downstream-latency; dep=my-app-postgresql; alert=KubeGuardDownstreamLatencyHigh
      experiment=backend-pod-db-latency.yaml ;;
    # Not a chaos manifest: a new revision of the backend with an invalid setting (as in PR #51).
    bad-deploy) rule=pod-crashloop; dep=pods; alert=KubeGuardPodCrashLooping; experiment="" ;;
    container-kill) rule=pod-crashloop; dep=pods; alert=KubeGuardPodCrashLooping
      experiment=backend-container-kill.yaml ;;
    p95) rule=service-latency; dep="GET /api/quote"; alert=KubeGuardServiceLatencyHigh
      experiment=service-latency.yaml ;;
    *) echo "unknown scenario '$1': latency, connectivity, db-latency, db-connectivity or p95" >&2; exit 1 ;;
  esac
}

states() { # one "STATE rule dependency" line per incident key
  api kubeguard:8080 /api/incidents |
    python3 -c 'import json, sys; [print(i["state"], i["rule"], i["dependency"]) for i in json.load(sys.stdin)]'
}

run_scenario() {
  local rule dep alert experiment also
  scenario "$1"
  is() { [ "$(state_of "$rule" "$dep")" = "$1" ]; }
  detected() { local s; s=$(state_of "$rule" "$dep"); [ "$s" = DEGRADED ] || [ "$s" = FIRING ]; }
  am_is() { [ "$(am_active "$alert" "$dep")" = "$1" ]; }
  count() { "$0" notifications 30m | grep -c "$1 *$alert [^ ]* -> $dep " || true; }
  notified() { [ "$(count "$1")" -gt "$2" ]; }

  echo "== scenario $1: $rule on -> $dep"
  if [ "${1#db-}" != "$1" ] && [ "$(samples_of "$rule" "$dep")" -lt 10 ]; then
    echo "FAILED: KubeGuard sees no spans from my-app to its database. Is the backend running" >&2
    echo "        with JDBC tracing and the otel-collector with kubeguard.enabled (see README.md)?" >&2
    exit 1
  fi
  local unhealthy
  unhealthy=$(states | grep -vE '^(NORMAL|RECOVERED) ' || true)
  [ -z "$unhealthy" ] || { echo "FAILED: not healthy before the scenario:"; echo "$unhealthy"; exit 1; } >&2
  am_is 0 || { echo "FAILED: $alert already active in Alertmanager" >&2; exit 1; }
  echo "  healthy: all $(states | wc -l | tr -d ' ') incident keys NORMAL/RECOVERED, no active $alert"

  local firing_before resolved_before
  firing_before=$(count FIRING)
  resolved_before=$(count RESOLVED)
  t0=$SECONDS
  # Never leave the fault behind, whatever happens below.
  # shellcheck disable=SC2064 # expand now: $experiment is local and gone when the trap runs
  trap "kubectl delete -f '$EXPERIMENTS/$experiment' --ignore-not-found >/dev/null 2>&1" EXIT
  kubectl apply -f "$EXPERIMENTS/$experiment" >/dev/null
  echo "  chaos injected ($experiment)"
  wait_for "KubeGuard DEGRADED (condition met)" 60 detected
  wait_for "KubeGuard FIRING" 90 is FIRING
  wait_for "Alertmanager alert active" 30 am_is 1
  wait_for "webhook notification FIRING" 30 notified FIRING "$firing_before"
  local firing expected
  expected=$(printf 'FIRING %s\n' "$rule $dep" ${also:+"$also"} | sort)
  # A symptom key may fire a few seconds after the cause: give it one firing duration.
  firing_ok() { firing=$(states | grep '^FIRING ' | sort || true); [ "$firing" = "$expected" ]; }
  wait_for "exactly the expected keys FIRING" 45 firing_ok ||
    { echo "FAILED: expected FIRING:"; echo "$expected"; echo "got:"; echo "$firing"; exit 1; } >&2
  echo "  FIRING: $rule -> $dep${also:+ (symptom: $also)}"

  t0=$SECONDS
  kubectl delete -f "$EXPERIMENTS/$experiment" --wait=false >/dev/null
  echo "  chaos removed"
  wait_for "KubeGuard RECOVERED" 150 is RECOVERED
  all_clear() { ! states | grep -qE '^(DEGRADED|FIRING) '; }
  wait_for "all incident keys recovered" 90 all_clear
  wait_for "Alertmanager alert resolved" 30 am_is 0
  wait_for "webhook notification RESOLVED" 60 notified RESOLVED "$resolved_before"
  echo "== scenario $1 PASSED"
}

# The memory-leak scenario ("heal before it breaks") link by link: each step needs the one before. Read-only.
heap_check() {
  local bns=my-namespace
  echo "== 1. Backend heap per pod (/q/metrics: jvm_memory_*_bytes, area=heap, tagged with the pod)"
  for pod in $(kubectl -n "$bns" get pods -l app.kubernetes.io/name=my-app -o jsonpath='{.items[*].metadata.name}'); do
    kubectl --request-timeout=10s get --raw "/api/v1/namespaces/$bns/pods/$pod:8080/proxy/q/metrics" 2>&1 | POD="$pod" python3 -c '
import os, re, sys
used = mx = 0.0; tagged = set(); seen = False
for line in sys.stdin:
    m = re.match(r"jvm_memory_(used|max)_bytes\{(.*)\} (\S+)$", line.strip())
    if not m or "area=\"heap\"" not in m.group(2):
        continue
    seen = True
    tag = re.search(r"pod=\"([^\"]*)\"", m.group(2))
    if tag: tagged.add(tag.group(1))
    value = float(m.group(3))
    if m.group(1) == "used": used += value
    elif value > 0: mx += value
pod = os.environ["POD"]
if not seen:
    print("  %s: no heap metrics (backend image without Micrometer, or not reachable)" % pod)
else:
    note = "pod tag ok" if tagged == {pod} else "PROBLEM: pod tag %s, expected %s (backend image before #89?)" % (sorted(tagged) or "missing", pod)
    print("  %s: heap %.0f of %.0f MiB (%.0f%%), %s" % (pod, used / 2**20, mx / 2**20, 100 * used / mx if mx else 0, note))'
  done
  echo "== 2. Collector: heap pipeline loaded, export errors in the last 15 min"
  if kubectl -n "$bns" get configmap otel-collector-config -o jsonpath='{.data.config\.yaml}' | grep -q "metrics/kubeguard-heap"; then
    echo "  pipeline metrics/kubeguard-heap is in the config"
  else
    echo "  PROBLEM: no metrics/kubeguard-heap pipeline in the config (helm/otel-collector not deployed since #89?)"
  fi
  kubectl -n "$bns" logs deploy/otel-collector --since=15m 2>&1 | grep -i -E "error|fail" | grep -i -E "kubeguard|prometheus" | tail -3 | sed 's/^/  /' || true
  echo "== 3. KubeGuard: heap samples received since its start"
  api kubeguard:8080 /q/metrics | grep -E '^kubeguard_heap_samples_received_total' | sed 's/^/  /' \
    || echo "  PROBLEM: no heap counter (KubeGuard image before 0.7.0 / sha-853b6d8?)"
  echo "== 4. KubeGuard: memory forecast"
  incidents | python3 -c '
import json, sys
rows = [i for i in json.load(sys.stdin) if i["rule"] == "memory-exhaustion"]
if not rows:
    print("  PROBLEM: no memory-exhaustion incident key: no heap sample has reached KubeGuard since it started")
for i in rows:
    e = i.get("lastEvaluation") or {}
    print("  %-9s %s: %s" % (i["state"], i["service"], e.get("explanation", "")))'
  echo "== 5. Leak load job"
  kubectl -n "$bns" get job memory-leak-load --ignore-not-found -o custom-columns='JOB:.metadata.name,ACTIVE:.status.active,DONE:.status.succeeded,FAILED:.status.failed,STARTED:.status.startTime' 2>&1 | sed 's/^/  /'
  kubectl -n "$bns" logs job/memory-leak-load --tail=5 2>/dev/null | sed 's/^/  /' || echo "  (no load job: start it from the showcase or kubectl apply -f chaos-mesh/experiments/memory-leak/load.yaml)"
  echo "== 6. Backend restarts and rollouts (each one empties the heap and ends the load)"
  kubectl -n "$bns" get pods -l app.kubernetes.io/name=my-app -o custom-columns='POD:.metadata.name,STARTED:.status.startTime,RESTARTS:.status.containerStatuses[0].restartCount,LAST:.status.containerStatuses[0].lastState.terminated.reason' | sed 's/^/  /'
}

case "${1:-status}" in
  run) run_scenario "${2:-}" ;;
  respond) run_respond "${2:-}" ;;
  responder) responder_runs ;;
  heap) heap_check ;;
  status) status ;;
  inject)
    scenario "${2:-}"
    if [ -n "$experiment" ]; then kubectl apply -f "$EXPERIMENTS/$experiment"; else bad_deploy start; fi ;;
  recover) kubectl delete -f "$EXPERIMENTS" --ignore-not-found; bad_deploy stop ;;
  watch)
    end=$((SECONDS + ${2:-600}))
    while [ $SECONDS -lt $end ]; do status; echo; sleep 5; done ;;
  notifications)
    k logs deploy/alert-sink --since="${2:-30m}" | python3 -c '
import json, sys
for line in sys.stdin:
    try:
        req = json.loads(line)
        body = req["json"] if "json" in req else json.loads(req.get("body", "{}"))
    except Exception:
        continue
    for a in body.get("alerts", []):
        l = a["labels"]
        print("  %-9s %s %s -> %s  startsAt=%s endsAt=%s" % (a["status"].upper(), l.get("alertname"), l.get("service"), l.get("dependency"), a["startsAt"], a["endsAt"]))' ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
