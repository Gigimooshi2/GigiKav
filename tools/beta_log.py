#!/usr/bin/env python3
"""Read Kav Beta's live log from its ntfy.sh topic.

    python3 tools/beta_log.py <topic> [--since 12h] [--type live,fix] [--raw]

Kav Beta posts batches of JSON lines, gzipped and base64'd (tagged "gz"). ntfy.sh keeps
messages for about 12 hours, so read within that window. The topic name is in Kav Beta's
Settings; it is private, keep it out of the repo.
"""
import argparse, base64, datetime, gzip, json, sys, urllib.request

def fetch(topic, since):
    url = f"https://ntfy.sh/{topic}/json?poll=1&since={since}"
    with urllib.request.urlopen(url, timeout=30) as r:
        for line in r.read().decode().splitlines():
            m = json.loads(line)
            if m.get("event") != "message":
                continue
            body = m.get("message", "")
            if "gz" in (m.get("tags") or []):
                body = gzip.decompress(base64.b64decode(body)).decode()
            for l in body.splitlines():
                if l.strip():
                    yield json.loads(l)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("topic")
    ap.add_argument("--since", default="12h")
    ap.add_argument("--type", default="")
    ap.add_argument("--raw", action="store_true")
    a = ap.parse_args()
    want = set(filter(None, a.type.split(",")))
    for e in sorted(fetch(a.topic, a.since), key=lambda e: e.get("t", 0)):
        if want and e.get("e") not in want:
            continue
        if a.raw:
            print(json.dumps(e, ensure_ascii=False)); continue
        t = datetime.datetime.fromtimestamp(e.pop("t", 0) / 1000).strftime("%H:%M:%S")
        print(t, e.pop("e", "?"), json.dumps(e, ensure_ascii=False))

if __name__ == "__main__":
    sys.exit(main())
