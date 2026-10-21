#!/usr/bin/env python3
"""
Build the bundled OpenStreetMap camera snapshot for Drive Alert.

Downloads traffic-enforcement cameras in India from the public Overpass API,
grouped by state / union territory, and writes a compact JSON file that the
app imports on first launch (app/src/main/assets/osm/india_cameras.json).

Data: (c) OpenStreetMap contributors, available under the Open Database
Licence (ODbL) 1.0 - https://www.openstreetmap.org/copyright
The output is a derivative database and must stay available under ODbL.

Only the tags the app uses are kept; contributor user names / ids are dropped.

Usage:
    python tools/fetch_osm_cameras.py [--out PATH] [--endpoint URL] [--force]

Refuses to overwrite the snapshot when a state that had cameras disappears or
loses most of them (likely a truncated Overpass result); --force publishes anyway.
"""
import argparse
import json
import os
import sys
import time
import urllib.parse
import urllib.request

DEFAULT_ENDPOINT = "https://overpass-api.de/api/interpreter"
DEFAULT_OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "osm", "india_cameras.json")
USER_AGENT = "DriveAlert-data-builder/1.0 (+https://github.com/Ansh1040/map)"

# One block per state: the state area (tags only), the cameras, then the
# skeleton coordinates of nodes referenced by enforcement relations.
QUERY = """
[out:json][timeout:300];
area["ISO3166-1"="IN"][admin_level=2]->.india;
rel(area.india)["admin_level"="4"]["ISO3166-2"~"^IN-"];
map_to_area->.states;
foreach.states->.state(
  .state out tags;
  (
    node(area.state)["highway"="speed_camera"];
    node(area.state)["surveillance:type"="ALPR"];
    relation(area.state)["type"="enforcement"];
  )->.found;
  .found out meta;
  node(r.found)->.members;
  .members out skel;
);
"""

KEEP_TAGS = {
    "highway", "enforcement", "maxspeed", "direction", "camera:direction",
    "surveillance", "surveillance:type", "name", "name:en", "ref", "addr:city",
    "operator", "ISO3166-2", "type",
}


MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]


def fetch(endpoint: str) -> tuple:
    """Returns (response, endpoint used). Retries busy servers, then tries mirrors."""
    body = urllib.parse.urlencode({"data": QUERY}).encode("utf-8")
    candidates = [endpoint] + [m for m in MIRRORS if m != endpoint]
    last_error = None
    for url in candidates:
        for attempt in range(2):
            try:
                request = urllib.request.Request(url, data=body, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(request, timeout=360) as response:
                    data = json.load(response)
                # Overpass reports timeouts / out-of-memory as HTTP 200 with a "remark" and
                # partial output. Never accept that: it would publish truncated states.
                remark = data.get("remark") or ""
                if "error" in remark.lower() or not data.get("osm3s", {}).get("timestamp_osm_base"):
                    raise RuntimeError(f"incomplete Overpass result: {remark or 'no osm_base'}")
                return data, url
            except Exception as error:  # 429/502/504 are common on public instances
                last_error = error
                print(f"{url} attempt {attempt + 1} failed: {error}", file=sys.stderr)
                time.sleep(15)
    raise SystemExit(f"all Overpass endpoints failed: {last_error}")


def compact(element: dict) -> dict:
    out = {"type": element["type"], "id": element["id"]}
    for key in ("lat", "lon", "timestamp"):
        if key in element:
            out[key] = element[key]
    tags = {k: v for k, v in element.get("tags", {}).items() if k in KEEP_TAGS}
    if tags:
        out["tags"] = tags
    if "members" in element:
        out["members"] = [
            {"type": m["type"], "ref": m["ref"], "role": m.get("role", "")}
            for m in element["members"] if m["type"] == "node"
        ]
    return out


def region_counts(elements: list) -> dict:
    """Camera elements per ISO3166-2 region (elements follow their region's area marker)."""
    counts, current = {}, None
    for element in elements:
        if element.get("type") == "area":
            current = element.get("tags", {}).get("ISO3166-2")
            counts.setdefault(current, 0)
        elif element.get("tags") and current is not None:
            counts[current] += 1
    return counts


def sanity_problems(previous: dict, current: dict) -> list:
    """Reasons to refuse publishing the new snapshot compared with the previous one.

    Flags a region that had cameras and is gone, and a region with at least 5 cameras
    that is emptied or loses more than 5 cameras and more than half of them. Smaller
    changes are normal OSM edits (e.g. duplicates removed).
    """
    problems = []
    for region, before in previous.items():
        after = current.get(region)
        if after is None:
            if before > 0:
                problems.append(f"{region} missing (had {before} cameras)")
        elif before >= 5 and (after == 0 or (before - after > 5 and after < before * 0.5)):
            problems.append(f"{region} dropped from {before} to {after} cameras")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--force", action="store_true", help="publish even if a state lost most of its cameras")
    args = parser.parse_args()

    raw, used = fetch(args.endpoint)
    elements = [compact(e) for e in raw.get("elements", [])]
    states = sum(1 for e in elements if e["type"] == "area")
    cameras = sum(1 for e in elements if e["type"] != "area" and "tags" in e)
    snapshot = {
        "format": "drive-alert-osm-snapshot/1",
        "attribution": "© OpenStreetMap contributors",
        "license": "ODbL-1.0",
        "license_url": "https://www.openstreetmap.org/copyright",
        "source": used,
        "osm_base": raw.get("osm3s", {}).get("timestamp_osm_base"),
        "elements": elements,
    }
    if os.path.exists(args.out) and not args.force:
        with open(args.out, encoding="utf-8") as handle:
            previous = region_counts(json.load(handle).get("elements", []))
        problems = sanity_problems(previous, region_counts(elements))
        if problems:
            print("refusing to overwrite the snapshot (use --force if the change is real):", file=sys.stderr)
            for problem in problems:
                print("  - " + problem, file=sys.stderr)
            return 2
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(snapshot, handle, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {args.out}: {states} states/UTs, {cameras} camera elements, osm_base={snapshot['osm_base']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
