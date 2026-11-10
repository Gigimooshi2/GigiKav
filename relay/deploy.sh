#!/usr/bin/env bash
# Deploy Kav's Places relay to Cloudflare Workers through the API.
# Needs a Cloudflare API token with Workers edit rights (sent as Authorization: Bearer; in Claude
# cloud sessions the network proxy adds it). The Places key itself is a Worker secret, PLACES_KEY,
# set once in the Cloudflare dashboard; redeploys keep it.
set -euo pipefail
ACCOUNT="${CF_ACCOUNT:-b50f8da63b675f84bd5275b0b4fdda62}"
NAME=kav-places
API="https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/workers/scripts/$NAME"
AUTH=()
[ -n "${CF_API_TOKEN:-}" ] && AUTH=(-H "Authorization: Bearer $CF_API_TOKEN")
cd "$(dirname "$0")"
# KV namespace for the usage summary (created once).
KV=$(curl -sS "${AUTH[@]}" "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/storage/kv/namespaces?per_page=100" \
  | python3 -c "import sys,json;print(next((n['id'] for n in json.load(sys.stdin)['result'] if n['title']=='kav-places-stats'),''))")
if [ -z "$KV" ]; then
  KV=$(curl -sS "${AUTH[@]}" -X POST "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/storage/kv/namespaces" \
    -H "content-type: application/json" -d '{"title":"kav-places-stats"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['id'])")
fi
TAG=$(curl -sS "${AUTH[@]}" "$API/settings" | python3 -c "import sys,json;d=json.load(sys.stdin);print((d.get('result') or {}).get('migration_tag') or '')" 2>/dev/null || true)
python3 - "$TAG" "$KV" > metadata.json <<'PY'
import json,sys
tag=sys.argv[1]; kv=sys.argv[2]
m={"main_module":"places-worker.js","compatibility_date":"2025-09-01",
   "bindings":[{"type":"durable_object_namespace","name":"USAGE","class_name":"Usage"},
               {"type":"kv_namespace","name":"STATS","namespace_id":kv},
               {"type":"plain_text","name":"PER_INSTALL_DAY","text":"200"},
               {"type":"plain_text","name":"GLOBAL_DAY","text":"3000"}],
   "keep_bindings":["secret_text"]}
if not tag: m["migrations"]={"new_tag":"v1","new_sqlite_classes":["Usage"]}
print(json.dumps(m))
PY
curl -sS "${AUTH[@]}" -X PUT "$API" \
  -F "metadata=@metadata.json;type=application/json" \
  -F "places-worker.js=@places-worker.js;type=application/javascript+module" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('deploy', d['success'], d.get('errors'))"
curl -sS "${AUTH[@]}" -X POST "$API/subdomain" -H "content-type: application/json" -d '{"enabled":true}' \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('workers.dev', d['success'], d.get('errors'))"
rm -f metadata.json
