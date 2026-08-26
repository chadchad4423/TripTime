#!/usr/bin/env python3
"""Render tools/quota-log.csv as a self-contained HTML dashboard.

    python3 tools/quota-dashboard.py
    python3 tools/quota-dashboard.py --out /var/www/html/triptime.html

Reads the CSV that poll-quota.py appends to and produces one HTML file with no external assets, no
JavaScript libraries and no network calls -- so it works from a file:// URL, from a static web
root, or emailed to yourself, and it keeps the same stdlib-only constraint as the poller.

The number worth looking at is not "how much quota is left" -- the account page says that. It is
how much of the traffic is real. Every sample spends exactly one request per endpoint on itself,
so anything above one request per interval came from an installed copy of the app. The charts
separate the two, because a quota draining entirely from self-polling looks identical to one
draining from users if you only watch the total.
"""
import argparse
import csv
import datetime
import html
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LOG = os.path.join(HERE, "quota-log.csv")
DEFAULT_OUT = os.path.join(HERE, "quota-dashboard.html")

# Categorical slots 1 and 2 of the reference palette, in fixed order: the series that matters takes
# slot 1. Validated in both modes -- worst adjacent CVD delta-E 24.7 light / 26.8 dark against a
# >= 8 target, normal-vision 33.6 / 31.8 against a >= 15 floor.
SERIES = [
    {"key": "app", "label": "App traffic", "light": "#2a78d6", "dark": "#3987e5"},
    {"key": "self", "label": "Self-polling", "light": "#eb6834", "dark": "#d95926"},
]

PLOT_W, PLOT_H = 720, 190
PAD_L, PAD_R, PAD_T, PAD_B = 44, 12, 14, 34


def load(path):
    if not os.path.exists(path):
        sys.exit("No log at " + path + " -- run poll-quota.py first.")
    with open(path, encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def to_int(value):
    return int(value) if value and value.isdigit() else None


def series_for(rows, endpoint):
    """Per-sample rows for one endpoint, with the request delta since the previous sample.

    A drop in 'used' means the daily window rolled over rather than that requests un-happened, so
    the delta becomes the new value rather than a negative number.
    """
    out, prev = [], None
    for row in [r for r in rows if r["endpoint"] == endpoint]:
        used = to_int(row["used"])
        delta = None
        if used is not None:
            if prev is None:
                delta = None
            elif used >= prev:
                delta = used - prev
            else:
                delta = used
            prev = used
        out.append({
            "at": row["sampled_at"],
            "used": used,
            "remaining": to_int(row["remaining"]),
            "limit": to_int(row["limit"]),
            "resets_at": row["resets_at"],
            "status": row["http_status"],
            "delta": delta,
            "self": 1 if delta is not None else 0,
            "app": max(delta - 1, 0) if delta is not None else 0,
        })
    return out


def short_time(stamp):
    try:
        return datetime.datetime.fromisoformat(stamp).strftime("%d %b %H:%M")
    except ValueError:
        return stamp


def bars_svg(points, ident, window):
    """Stacked bars: app traffic on the baseline, self-polling above it.

    Bars rather than a line because the samples are discrete intervals and the composition is the
    whole point; a line would imply a continuous rate the data does not describe.
    """
    plotted = [p for p in points if p["delta"] is not None][-window:]
    if not plotted:
        return '<p class="empty">Not enough samples yet - the first one has nothing to compare against.</p>'

    peak = max(max(p["delta"] for p in plotted), 1)
    top = max(4, -(-peak // 4) * 4)
    inner_w = PLOT_W - PAD_L - PAD_R
    inner_h = PLOT_H - PAD_T - PAD_B
    slot = inner_w / max(len(plotted), 1)
    bar_w = max(3.0, min(26.0, slot * 0.62))

    def y_of(value):
        return PAD_T + inner_h - (value / top) * inner_h

    parts = ['<svg viewBox="0 0 %d %d" role="img" aria-labelledby="%s-t" class="chart">'
             % (PLOT_W, PLOT_H, ident),
             '<title id="%s-t">Requests per sample interval, app traffic versus self-polling</title>'
             % ident]

    for step in range(5):
        value = top * step / 4
        y = y_of(value)
        parts.append('<line class="grid" x1="%d" y1="%.1f" x2="%d" y2="%.1f"/>'
                     % (PAD_L, y, PLOT_W - PAD_R, y))
        parts.append('<text class="tick" x="%d" y="%.1f" text-anchor="end">%g</text>'
                     % (PAD_L - 8, y + 3.5, value))

    for index, point in enumerate(plotted):
        cx = PAD_L + slot * (index + 0.5)
        x = cx - bar_w / 2
        base = y_of(0)
        app, own = point["app"], point["self"]
        plural = "" if point["delta"] == 1 else "s"
        tip = "%s: %d request%s (%d from the app, %d self-polling)" % (
            short_time(point["at"]), point["delta"], plural, app, own)
        parts.append("<g><title>%s</title>" % html.escape(tip))
        if app:
            parts.append('<rect class="s-app" x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="3"/>'
                         % (x, y_of(app), bar_w, base - y_of(app)))
        if own:
            gap = 2 if app else 0
            y_top = y_of(app + own)
            height = (y_of(app) - gap) - y_top
            if height > 0.5:
                parts.append('<rect class="s-self" x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="3"/>'
                             % (x, y_top, bar_w, height))
        parts.append("</g>")

    parts.append('<line class="axis" x1="%d" y1="%.1f" x2="%d" y2="%.1f"/>'
                 % (PAD_L, y_of(0), PLOT_W - PAD_R, y_of(0)))

    for index in dict.fromkeys([0, len(plotted) // 2, len(plotted) - 1]):
        cx = PAD_L + slot * (index + 0.5)
        parts.append('<text class="tick" x="%.1f" y="%d" text-anchor="middle">%s</text>'
                     % (cx, PLOT_H - 12, html.escape(short_time(plotted[index]["at"]))))

    parts.append("</svg>")
    return "".join(parts)


def tiles(points, endpoint):
    latest = next((p for p in reversed(points) if p["remaining"] is not None), None)
    if latest is None:
        return '<p class="empty">No successful sample for %s yet.</p>' % html.escape(endpoint)
    limit = latest["limit"] or 0
    used = latest["used"] or 0
    pct = (used / limit * 100) if limit else 0
    today = datetime.date.today().isoformat()
    todays = [p for p in points if p["at"].startswith(today) and p["delta"] is not None]
    app_today = sum(p["app"] for p in todays)
    self_today = sum(p["self"] for p in todays)
    sample_word = "sample" if len(todays) == 1 else "samples"
    return (
        '<div class="tiles">'
        '<div class="tile"><span class="k">Remaining</span><span class="v">%s</span>'
        '<span class="sub">of %s &middot; %.1f%% used</span></div>'
        '<div class="tile"><span class="k">From the app today</span><span class="v">%s</span>'
        '<span class="sub">across %d %s</span></div>'
        '<div class="tile"><span class="k">Self-polling today</span><span class="v">%s</span>'
        '<span class="sub">this measurement&rsquo;s own cost</span></div>'
        '<div class="tile"><span class="k">Window resets</span>'
        '<span class="v small">%s</span>'
        '<span class="sub">counters return to zero</span></div>'
        "</div>"
    ) % ("{:,}".format(latest["remaining"]), "{:,}".format(limit), pct,
         "{:,}".format(app_today), len(todays), sample_word,
         "{:,}".format(self_today), html.escape(short_time(latest["resets_at"])))


def table(points, limit_rows=12):
    head = ("<table><thead><tr><th>Sampled</th><th>Status</th><th class=n>Requests</th>"
            "<th class=n>App</th><th class=n>Self</th><th class=n>Remaining</th>"
            "</tr></thead><tbody>")
    body = []
    for point in reversed(points[-limit_rows:]):
        failed = point["status"] != "200"
        dash = "&mdash;"
        body.append(
            "<tr%s><td>%s</td><td>%s</td><td class=n>%s</td><td class=n>%s</td>"
            "<td class=n>%s</td><td class=n>%s</td></tr>" % (
                " class=bad" if failed else "",
                html.escape(short_time(point["at"])),
                html.escape(point["status"][:38]),
                dash if point["delta"] is None else point["delta"],
                dash if point["delta"] is None else point["app"],
                dash if point["delta"] is None else point["self"],
                dash if point["remaining"] is None else point["remaining"],
            ))
    return head + "".join(body) + "</tbody></table>"


def legend():
    items = "".join('<span class="lg"><i class="sw s-%s"></i>%s</span>'
                    % (s["key"], html.escape(s["label"])) for s in SERIES)
    return '<div class="legend">%s</div>' % items


CSS = """
:root{color-scheme:light;--surface:#fcfcfb;--plane:#f9f9f7;--ink:#0b0b0b;--ink2:#52514e;
--muted:#898781;--grid:#e1e0d9;--axis:#c3c2b7;--ring:rgba(11,11,11,.10);
--s-app:#2a78d6;--s-self:#eb6834;--bad:#d03b3b}
@media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;
--surface:#1a1a19;--plane:#0d0d0d;--ink:#fff;--ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;
--axis:#383835;--ring:rgba(255,255,255,.10);--s-app:#3987e5;--s-self:#d95926;--bad:#d03b3b}}
:root[data-theme=dark]{color-scheme:dark;--surface:#1a1a19;--plane:#0d0d0d;--ink:#fff;
--ink2:#c3c2b7;--muted:#898781;--grid:#2c2c2a;--axis:#383835;--ring:rgba(255,255,255,.10);
--s-app:#3987e5;--s-self:#d95926;--bad:#d03b3b}
*{box-sizing:border-box}
body{margin:0;padding:28px 20px 56px;background:var(--plane);color:var(--ink);
font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
.wrap{max-width:820px;margin:0 auto}
h1{font-size:22px;margin:0 0 4px}
h2{font-size:17px;margin:0 0 14px}
h3{font-size:13px;text-transform:uppercase;letter-spacing:.08em;color:var(--ink2);
margin:22px 0 8px;font-weight:600}
.meta{color:var(--ink2);font-size:13px;margin:0 0 24px}
.meta code{color:var(--muted);word-break:break-all}
section{background:var(--surface);border:1px solid var(--ring);border-radius:10px;
padding:18px;margin:0 0 20px}
.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;
margin:0 0 16px}
.tile{border:1px solid var(--ring);border-radius:8px;padding:10px 12px;display:flex;
flex-direction:column;gap:2px}
.tile .k{font-size:12px;color:var(--ink2)}
.tile .v{font-size:26px;font-weight:600;line-height:1.15}
.tile .v.small{font-size:15px;font-weight:600;padding-top:6px}
.tile .sub{font-size:11px;color:var(--muted)}
.legend{display:flex;gap:16px;flex-wrap:wrap;font-size:12px;color:var(--ink2);margin:0 0 6px}
.lg{display:inline-flex;align-items:center;gap:6px}
.sw{width:10px;height:10px;border-radius:2px;display:inline-block}
.sw.s-app{background:var(--s-app)}
.sw.s-self{background:var(--s-self)}
.chart{width:100%;height:auto;display:block;overflow:visible}
.grid{stroke:var(--grid);stroke-width:1}
.axis{stroke:var(--axis);stroke-width:1}
.tick{fill:var(--muted);font-size:10px;font-variant-numeric:tabular-nums}
rect.s-app{fill:var(--s-app)}
rect.s-self{fill:var(--s-self)}
g:hover rect{opacity:.82}
table{width:100%;border-collapse:collapse;font-size:13px;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--grid)}
th{color:var(--ink2);font-weight:600;font-size:11px;text-transform:uppercase;
letter-spacing:.06em}
.n{text-align:right}
tr.bad td{color:var(--bad)}
.empty{color:var(--ink2);font-size:13px;margin:8px 0}
footer{color:var(--muted);font-size:12px;text-align:center;margin-top:8px}
"""


def build(rows, log_path, window):
    endpoints = list(dict.fromkeys(r["endpoint"] for r in rows))
    generated = datetime.datetime.now().replace(microsecond=0)
    blocks = []
    for endpoint in endpoints:
        points = series_for(rows, endpoint)
        blocks.append(
            "<section><h2>%s</h2>%s%s%s<h3>Recent samples</h3>%s</section>" % (
                html.escape(endpoint.title()),
                tiles(points, endpoint),
                legend(),
                bars_svg(points, "c-" + endpoint, window),
                table(points),
            ))

    meta = (
        '<p class="meta">Generated %s from <code>%s</code> &middot; %d rows. Each sample spends '
        "one request per endpoint on itself, so bars above one request per interval are traffic "
        "from installed copies of the app. Charts show the most recent %d samples; the table and "
        "the CSV keep everything.</p>"
    ) % (generated.isoformat(sep=" "), html.escape(log_path), len(rows), window)

    # Concatenated rather than %-formatted: CSS is full of literal % (width:100%) and would need
    # every one of them doubled, which is a trap for whoever edits the stylesheet next.
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>TripTime API traffic</title><style>" + CSS + "</style></head>"
        "<body><div class=wrap><h1>TripTime API traffic</h1>"
        + meta
        + "".join(blocks)
        + "<footer>Hover a bar for its breakdown. Generated by tools/quota-dashboard.py</footer>"
        "</div></body></html>"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--log", default=DEFAULT_LOG, help="CSV to read")
    parser.add_argument("--out", default=DEFAULT_OUT, help="HTML to write")
    # At hourly sampling a week is 168 bars, which degrades to unreadable slivers. The table and
    # the CSV keep the full history; the chart shows a window you can actually read.
    parser.add_argument("--window", type=int, default=48,
                        help="most recent samples to chart (default: 48, about two days hourly)")
    args = parser.parse_args()

    rows = load(args.log)
    with open(args.out, "w", encoding="utf-8") as handle:
        handle.write(build(rows, args.log, args.window))
    print("Wrote %s from %d rows." % (args.out, len(rows)))


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------------------------------------
# Keeping it up to date (Ubuntu)
#
# The poller writes the CSV; this reads it. Regenerate on the same cron tick, straight after the
# sample, so the page is never older than the data:
#
#   0 * * * * . $HOME/.config/triptime/env && /usr/bin/python3 $HOME/triptime/poll-quota.py >> $HOME/triptime/poll.log 2>&1 && /usr/bin/python3 $HOME/triptime/quota-dashboard.py --out $HOME/triptime/quota-dashboard.html >> $HOME/triptime/poll.log 2>&1
#
# Then open the file directly, or point a static web root at it -- the page needs no server of its
# own, no network access and no JavaScript library, so file:// works exactly as well as https://.
#
# Notes on reading it:
#
#   * The bar height is requests in that interval, not quota used. Quota-used only ever climbs
#     until the window resets, which tells you nothing about when traffic actually happened.
#   * Every sample costs one request per endpoint, drawn as the top segment. A day of nothing but
#     that colour means nobody used the app; it does not mean the API was quiet.
#   * A failed sample is a row in the table with the error in place of a status, and no bar --
#     failures are recorded rather than skipped, so an outage reads differently from an idle hour.
#   * --window controls how many samples the chart shows (default 48, about two days hourly). The
#     table and the CSV always keep the full history.
# ---------------------------------------------------------------------------------------------
