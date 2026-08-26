#!/usr/bin/env python3
"""Record OpenRouteService quota use over time (DECISIONS.md D-019, D-021).

    python tools/poll-quota.py            # take one sample, append to the log
    python tools/poll-quota.py --show     # print the log, newest last
    python tools/poll-quota.py --summary  # today's usage per endpoint

HeiGIT counts every request against your key -- enforcing a daily quota requires it -- but exposes
no history: the account page shows a snapshot, and the X-Ratelimit-* response headers describe
only the current window before resetting. This samples those headers and appends them to a CSV, so
the history exists locally even though the provider does not offer one.

Why bother: it answers the one question the resilience work kept running into -- not "how many
installs are there" (release download counts already say that) but "how hard are those installs
hitting the shared quota", which is what decides whether the proxy deferred in D-019 is ever
warranted.

Cost: each sample spends 1 geocoding unit and 1 directions unit, because those counters are
separate and the only way to read either is to make a request against it. Hourly polling is about
24 of each per day, against budgets of roughly 1000-3000 and 2000. Do not poll more often than
hourly -- you would be measuring yourself more than your users.

Stdlib only, so there is nothing to install.
"""
import argparse
import csv
import datetime
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_LOG = os.path.join(HERE, "quota-log.csv")
FIELDS = ["sampled_at", "endpoint", "http_status", "limit", "remaining", "used", "resets_at"]

# Deliberately tiny requests: one address lookup capped at a single result, and a route between two
# points a few streets apart. Both are the cheapest valid call to their respective counter.
PROBES = {
    "geocoding": "/pelias/v1/search?text=Denver&size=1",
    "directions": "/openrouteservice/v2/directions/driving-car"
                  "?start=-104.9903,39.7392&end=-104.9847,39.7407",
}


def read_api_key():
    path = os.path.join(ROOT, "local.properties")
    if not os.path.exists(path):
        sys.exit("local.properties not found -- run this from the project root.")
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            if line.startswith("ORS_API_KEY="):
                key = line.split("=", 1)[1].strip()
                if key:
                    return key
    sys.exit("ORS_API_KEY not found (or empty) in local.properties.")


def read_base():
    """Follow the same config the app uses, so the poll tracks wherever it is actually pointed."""
    config = os.path.join(ROOT, "docs", "config.json")
    try:
        with open(config, encoding="utf-8") as handle:
            return json.load(handle).get("apiBase", "https://api.heigit.org").rstrip("/")
    except (OSError, ValueError):
        return "https://api.heigit.org"


def sample(base, api_key):
    rows = []
    stamp = datetime.datetime.now().replace(microsecond=0).isoformat()
    for name, path in PROBES.items():
        url = f"{base}{path}&api_key={urllib.parse.quote(api_key)}"
        status, headers = "error", {}
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                status, headers = response.status, dict(response.headers)
        except urllib.error.HTTPError as err:
            # A 4xx still carries the rate-limit headers, and a 429 is exactly when you want them.
            status, headers = err.code, dict(err.headers)
        except Exception as err:  # network down, DNS, timeout -- record the gap, do not crash
            rows.append({
                "sampled_at": stamp, "endpoint": name, "http_status": f"error: {err}",
                "limit": "", "remaining": "", "used": "", "resets_at": "",
            })
            continue

        lower = {k.lower(): v for k, v in headers.items()}
        limit = lower.get("x-ratelimit-limit", "")
        remaining = lower.get("x-ratelimit-remaining", "")
        reset = lower.get("x-ratelimit-reset", "")
        used = ""
        if limit.isdigit() and remaining.isdigit():
            used = str(int(limit) - int(remaining))
        if reset.isdigit():
            reset = datetime.datetime.fromtimestamp(int(reset)).replace(microsecond=0).isoformat()

        rows.append({
            "sampled_at": stamp, "endpoint": name, "http_status": str(status),
            "limit": limit, "remaining": remaining, "used": used, "resets_at": reset,
        })
    return rows


def append(rows, log_path):
    exists = os.path.exists(log_path)
    with open(log_path, "a", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        if not exists:
            writer.writeheader()
        writer.writerows(rows)


def load(log_path):
    if not os.path.exists(log_path):
        sys.exit(f"No log yet at {log_path} -- run without --show first.")
    with open(log_path, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--show", action="store_true", help="print the log and exit")
    parser.add_argument("--summary", action="store_true", help="print today's usage and exit")
    parser.add_argument("--log", default=DEFAULT_LOG, help=f"CSV path (default: {DEFAULT_LOG})")
    args = parser.parse_args()

    if args.show:
        for row in load(args.log):
            print(f"{row['sampled_at']}  {row['endpoint']:<11} used={row['used'] or '?':>5}"
                  f"  remaining={row['remaining'] or '?':>5}  resets {row['resets_at']}")
        return

    if args.summary:
        today = datetime.date.today().isoformat()
        rows = [r for r in load(args.log) if r["sampled_at"].startswith(today)]
        if not rows:
            print(f"No samples yet today ({today}).")
            return
        print(f"Usage on {today}, from {len(rows)} samples:")
        for endpoint in PROBES:
            mine = [r for r in rows if r["endpoint"] == endpoint]
            used = [int(r["used"]) for r in mine if r["used"].isdigit()]
            if not used:
                continue
            # Each endpoint has its own limit -- geocoding and directions differ -- so read it from
            # that endpoint's own rows rather than from whichever row happened to be first.
            limits = [r["limit"] for r in mine if r["limit"]]
            limit = limits[-1] if limits else "?"
            # Highest 'used' seen today is the day's peak; the counter resets, so this is the
            # meaningful figure rather than a sum across samples.
            print(f"  {endpoint:<11} peak used {max(used)} of {limit}"
                  f"   (self-polling accounts for about {len(used)} of that)")
        return

    rows = sample(read_base(), read_api_key())
    append(rows, args.log)
    for row in rows:
        print(f"{row['sampled_at']}  {row['endpoint']:<11} status={row['http_status']:<6}"
              f" used={row['used'] or '?'}  remaining={row['remaining'] or '?'}")


if __name__ == "__main__":
    main()
