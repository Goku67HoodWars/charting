# Sea Chart Route

**▶ Live app: <https://charting-ochre.vercel.app/>**

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
- One-click WikiSync lookup: pulls completed charts + Sailing level for any name
- Click tasks to mark them done; isolated tasks flagged separately
- Guided first-run walkthrough, responsive down to phone width

## Run locally

Double-click **`sail.py`** (needs Python 3). It serves the page and the
WikiSync lookup at <http://localhost:8731/index.html>.

## Deploy (live WikiSync, free, no credit card)

The page is static, but WikiSync blocks cross-origin reads, so live lookup
needs a tiny proxy. `api/wikisync.js` is that proxy — Vercel runs it for free.

1. Push this folder to a **GitHub repo**.
2. At <https://vercel.com>, sign up with GitHub (free Hobby plan, no card).
3. **Add New → Project → Import** your repo → **Deploy**. No settings needed —
   Vercel serves `index.html` and runs `api/wikisync.js` automatically.
4. Open your `*.vercel.app` URL. "Look up" now works for anyone.

Every `git push` redeploys automatically.

> A player's data only exists in WikiSync if they've opened RuneLite while
> logged in with the **WikiSync** plugin enabled.

## Files

- `index.html` — the whole app (map, routing, data, water mask all inline)
- `api/wikisync.js` — serverless WikiSync proxy (Vercel)
- `sail.py` — local server + proxy, for running without deploying
