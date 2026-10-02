// Vercel serverless function: proxies WikiSync so the static page can read a player's progress.
// WikiSync only allows cross-origin reads from *.runescape.wiki, and Cloudflare blocks non-browser
// user-agents — this runs server-side with a browser UA and re-serves the JSON with a CORS header.
export default async function handler(req, res) {
  const u = String(req.query.u || "").trim();
  res.setHeader("Access-Control-Allow-Origin", "*");
  if (!u) {
    res.status(400).json({ error: "missing username" });
    return;
  }
  const url =
    "https://sync.runescape.wiki/runelite/player/" +
    encodeURIComponent(u) +
    "/STANDARD";
  try {
    const r = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      },
    });
    const body = await r.text();
    res.setHeader("Content-Type", "application/json; charset=utf-8");
    // cache a found profile briefly at the edge to spare WikiSync repeat hits
    if (r.ok) res.setHeader("Cache-Control", "public, max-age=60");
    res.status(r.status).send(body);
  } catch (e) {
    res.status(502).json({ error: String(e) });
  }
}
