#!/usr/bin/env python3
"""Regenerate the plugin's bundled data from the web app's index.html (single source of truth).

Run from the repo root:  python plugin/tools/extract-data.py
Pulls the <script id="data"> and <script id="watermask"> blobs out of index.html and writes them
to plugin/src/main/resources/charting/ so the plugin and the web app always share one dataset.
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "charting")


def grab(html, tag_id):
    m = re.search(r'<script id="%s"[^>]*>(.*?)</script>' % re.escape(tag_id), html, re.S)
    if not m:
        raise SystemExit("could not find <script id=%r> in index.html" % tag_id)
    return json.loads(m.group(1).strip())


def main():
    html = open(os.path.join(REPO, "index.html"), encoding="utf-8").read()
    data = grab(html, "data")
    watermask = grab(html, "watermask")
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "tasks.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    with open(os.path.join(OUT, "watermask.json"), "w", encoding="utf-8") as f:
        json.dump(watermask, f, separators=(",", ":"))
    print("wrote %d tasks, %d seas, %d oceans" % (len(data["tasks"]), len(data["seas"]), len(data["oceans"])))
    print("watermask %dx%d cell=%d" % (watermask["gw"], watermask["gh"], watermask["cell"]))


if __name__ == "__main__":
    main()
