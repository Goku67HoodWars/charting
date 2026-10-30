// Vercel serverless function: returns the OSRS wiki's CURRENT map tile version, so the page always
// points at live tiles even after the wiki retires a version. The wiki bakes the version into its
// map page as .../osrs/versions/<VERSION>/tiles/rendered — we fetch that page and pull it out.
// Cross-origin + non-browser UA are blocked by Cloudflare, so this runs server-side with a browser UA
// and re-serves a tiny JSON with a CORS header. Cached at the edge so the wiki isn't hit per visit.
export default async function handler(req, res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  try {
    const r = await fetch("https://oldschool.runescape.wiki/w/RuneScape:Map", {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      },
    });
    if (!r.ok) {
      res.status(502).json({ error: "wiki HTTP " + r.status });
      return;
    }
    const html = await r.text();
    const m = html.match(/osrs\/versions\/(\d{4}-\d{2}-\d{2}_[a-z])\/tiles\/rendered/);
    if (!m) {
      res.status(502).json({ error: "version not found on wiki map page" });
      return;
    }
    // 6h fresh at the edge, serve stale for a day while revalidating — the version changes rarely.
    res.setHeader("Cache-Control", "public, max-age=21600, s-maxage=21600, stale-while-revalidate=86400");
    res.status(200).json({ version: m[1] });
  } catch (e) {
    res.status(502).json({ error: String(e) });
  }
}
