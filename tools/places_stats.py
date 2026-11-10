#!/usr/bin/env python3
"""Shared Places relay usage: reads the summary the relay saves to Cloudflare KV (key "stats").

Needs a Cloudflare API token with Workers KV read access, in CLOUDFLARE_API_TOKEN (or injected by a
proxy). Usage: tools/places_stats.py [days]
"""
import json
import os
import sys
import urllib.request

ACCOUNT = "b50f8da63b675f84bd5275b0b4fdda62"
NAMESPACE_TITLE = "kav-places-stats"
API = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/storage/kv/namespaces"


def get(url):
    req = urllib.request.Request(url)
    tok = os.environ.get("CLOUDFLARE_API_TOKEN")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read()


def main():
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 7
    spaces = json.loads(get(API + "?per_page=100"))["result"]
    ns = next((n["id"] for n in spaces if n["title"] == NAMESPACE_TITLE), None)
    if not ns:
        sys.exit("no stats namespace yet (deploy the relay with relay/deploy.sh)")
    try:
        stats = json.loads(get(f"{API}/{ns}/values/stats"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            sys.exit("no stats yet: nobody has searched through the relay")
        raise
    print("updated", stats["updated"])
    for day in sorted(stats["days"])[-days:]:
        d = stats["days"][day]
        req = d["requests"]
        sent = sum(n for k, n in req.items() if k.endswith(":sent"))
        auto = req.get("autocomplete:sent", 0)
        det = req.get("details:sent", 0)
        capped = sum(n for k, n in req.items() if "limit" in k)
        errors = {k: n for k, n in req.items() if ":google_" in k}
        print(f"{day}  installs {d['installs']:>4}  sent {sent:>5} (autocomplete {auto}, details {det})"
              f"  capped {capped}" + (f"  google errors {errors}" if errors else ""))
        if d["top"]:
            print("            busiest:", ", ".join(f"{i} {n}" for i, n in d["top"]))


if __name__ == "__main__":
    main()
