# Sea Chart Route

Interactive OSRS Sailing sea-charting route planner on the live wiki map. Pick
a start, filter by task type / ocean / boat unlocks, see water-aware routes with
duck & weather drifts, and sync any player's progress and Sailing level from
WikiSync.

## Run locally

Double-click **`sail.py`** (needs Python 3). It serves the page and the WikiSync
lookup at <http://localhost:8731/index.html>.

## Deploy free on Vercel (live WikiSync, no credit card)

The page is static, but WikiSync blocks cross-origin reads, so live lookup needs
a tiny proxy. `api/wikisync.js` is that proxy — Vercel runs it for free.

1. Push this folder to a **GitHub repo**.
2. Go to <https://vercel.com>, sign up with GitHub (free Hobby plan, no card).
3. **Add New → Project → Import** your repo → **Deploy**. No settings needed —
   Vercel serves `index.html` and runs `api/wikisync.js` automatically.
4. Open your `*.vercel.app` URL. "Look up" now works for anyone.

Every `git push` redeploys automatically.

> A player's data only exists in WikiSync if they've opened RuneLite while logged
> in with the **WikiSync** plugin enabled.

## Files

- `index.html` — the whole app (map, routing, data, water mask all inline)
- `api/wikisync.js` — serverless WikiSync proxy (Vercel)
- `sail.py` — local server + proxy, for running without deploying
