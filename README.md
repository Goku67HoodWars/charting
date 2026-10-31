# Sea Chart Route

**▶ Live app: <https://osrs-charting.vercel.app/>**

An interactive route planner for Old School RuneScape **Sailing** sea-charting,
drawn on the live wiki map. Pick a start, filter by task type / ocean / boat
unlocks, follow water-aware routes with duck & weather drifts, and sync any
player's completed charts and Sailing level straight from WikiSync.

This repository is the **core logic and source** behind the live app above —
the whole thing is a single static page plus a tiny WikiSync proxy. Vercel
builds and hosts it from here, and every push redeploys the live site.

## Features

- Water-only A\* routing (no cutting across land), smoothed, with direction arrows
- 2-opt route ordering to cut out backtracks
- Task filters by type, ocean, and boat unlocks (with tooltips)
- Current-duck and weather drifts drawn start → end
- WikiSync import: one-click lookup where allowed, with a copy-paste fallback (the wiki blocks server-side lookups)
- Click tasks to mark them done; isolated tasks flagged separately
- Guided first-run walkthrough, responsive down to phone width

## Run locally

Double-click **`sail.py`** (needs Python 3). It serves the page and the
WikiSync lookup at <http://localhost:8731/index.html>.

## Deploy (live WikiSync, free, no credit card)

The page is static, but WikiSync blocks cross-origin reads, so live lookup
needs a tiny proxy. `api/wikisync.js` is that proxy — Vercel runs it for free.
The wiki now also blocks requests from datacenter IPs, so when the proxy is
refused the app falls back to a copy-paste import: open your WikiSync page in
your own browser and paste the JSON. That path always works.

1. Push this folder to a **GitHub repo**.
2. At <https://vercel.com>, sign up with GitHub (free Hobby plan, no card).
3. **Add New → Project → Import** your repo → **Deploy**. No settings needed —
   Vercel serves `index.html` and runs `api/wikisync.js` automatically.
4. Open your `*.vercel.app` URL. **Look up** tries the proxy, then offers the
   paste-import if the wiki refuses it.

Every `git push` redeploys automatically.

> A player's data only exists in WikiSync if they've opened RuneLite while
> logged in with the **WikiSync** plugin enabled.

## Staying in sync with the wiki map

The wiki retires its map tile version every few months. The page fetches the
current version from `/api/mapversion` on load and switches to it automatically,
so the base map keeps working with no code change. `index.html` keeps a
last-known-good version as a fallback; if the lookup ever fails, the map uses
that fallback and shows a small red "fallback map version" badge in the corner.

## Files

- `index.html` — the whole app (map, routing, data, water mask all inline)
- `api/wikisync.js` — serverless WikiSync proxy (Vercel)
- `api/mapversion.js` — serverless lookup for the wiki's current map tile version (Vercel)
- `sail.py` — local server + proxies, for running without deploying
