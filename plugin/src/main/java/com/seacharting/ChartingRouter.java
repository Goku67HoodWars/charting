package com.seacharting;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Water-only route planner, ported from the web app. A* over a 1-bit-per-cell water mask, connected-
 * component labelling, and a nearest-neighbour + 2-opt tour that expands duck/weather tasks into drift
 * legs (duck one-way to its second point; weather a round-trip start -> second point -> start).
 */
public class ChartingRouter
{
	static final int DUCK = 2, WEATHER = 5;
	private static final int CORRIDOR = 44;

	private final int GW, GH, CELL, WX0, WY0;
	private final byte[] bits;
	private int[] comp;   // connected-component label per cell (-1 = land), built lazily

	private final float[] gScore;
	private final int[] seen;
	private final int[] came;
	private int stamp = 0;

	private final Map<String, double[][]> pathCache = new HashMap<>();
	private final Map<String, int[]> snapCache = new HashMap<>();

	ChartingRouter(Gson gson)
	{
		try (InputStream in = ChartingRouter.class.getResourceAsStream("/charting/watermask.json"))
		{
			if (in == null)
			{
				throw new IllegalStateException("watermask.json not found on the classpath");
			}
			JsonObject wm = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			GW = wm.get("gw").getAsInt();
			GH = wm.get("gh").getAsInt();
			CELL = wm.get("cell").getAsInt();
			WX0 = wm.get("x0").getAsInt();
			WY0 = wm.get("y0").getAsInt();
			bits = Base64.getDecoder().decode(wm.get("bits").getAsString());
		}
		catch (Exception e)
		{
			throw new RuntimeException("Failed to load water mask", e);
		}
		// hand-patched water the auto mask missed (same rectangles as the web app)
		int[][] patches = {{3205, 3132, 3281, 3137}};
		for (int[] p : patches)
		{
			int[] a = toCell(Math.min(p[0], p[2]), Math.min(p[1], p[3]));
			int[] b = toCell(Math.max(p[0], p[2]), Math.max(p[1], p[3]));
			for (int cx = a[0]; cx <= b[0]; cx++)
			{
				for (int cy = a[1]; cy <= b[1]; cy++)
				{
					if (inGrid(cx, cy))
					{
						int i = cellIdx(cx, cy);
						bits[i >> 3] |= (1 << (i & 7));
					}
				}
			}
		}
		gScore = new float[GW * GH];
		seen = new int[GW * GH];
		came = new int[GW * GH];
	}

	// ---- mask helpers ----
	private int cellIdx(int cx, int cy)
	{
		return cy * GW + cx;
	}

	private boolean inGrid(int cx, int cy)
	{
		return cx >= 0 && cy >= 0 && cx < GW && cy < GH;
	}

	private boolean isWaterCell(int cx, int cy)
	{
		if (!inGrid(cx, cy))
		{
			return true;   // off-grid = open ocean
		}
		int i = cellIdx(cx, cy);
		return (((bits[i >> 3] & 0xFF) >> (i & 7)) & 1) != 0;
	}

	private int[] toCell(double x, double y)
	{
		return new int[]{(int) Math.floor((x - WX0) / CELL), (int) Math.floor((y - WY0) / CELL)};
	}

	private double[] cellCenter(int cx, int cy)
	{
		return new double[]{WX0 + cx * CELL + CELL / 2.0, WY0 + cy * CELL + CELL / 2.0};
	}

	private int[] snapWater(double x, double y)
	{
		String key = x + "," + y;
		if (snapCache.containsKey(key))
		{
			return snapCache.get(key);
		}
		int[] c = toCell(x, y);
		int cx = c[0], cy = c[1];
		int[] res = null;
		if (inGrid(cx, cy) && isWaterCell(cx, cy))
		{
			res = new int[]{cx, cy};
		}
		else
		{
			for (int r = 1; r <= 16 && res == null; r++)
			{
				for (int dx = -r; dx <= r && res == null; dx++)
				{
					for (int dy = -r; dy <= r && res == null; dy++)
					{
						if (Math.max(Math.abs(dx), Math.abs(dy)) != r)
						{
							continue;
						}
						if (inGrid(cx + dx, cy + dy) && isWaterCell(cx + dx, cy + dy))
						{
							res = new int[]{cx + dx, cy + dy};   // only in-grid cells: A* can't route through off-grid open ocean
						}
					}
				}
			}
		}
		snapCache.put(key, res);
		return res;
	}

	// ---- A* (8-dir, no corner cut), 200k node cap -> null ----
	private double[][] astar(int ax, int ay, int bx, int by)
	{
		if (!inGrid(ax, ay) || !inGrid(bx, by))
		{
			return null;   // endpoints must be inside the grid, or the stamped arrays overflow
		}
		int start = cellIdx(ax, ay), goal = cellIdx(bx, by);
		stamp++;
		int s = stamp;
		PriorityQueue<double[]> heap = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
		gScore[start] = 0;
		seen[start] = s;
		came[start] = -1;
		heap.add(new double[]{h(start, bx, by), start});
		int pops = 0;
		final int cap = 200000;
		while (!heap.isEmpty())
		{
			int cur = (int) heap.poll()[1];
			if (++pops > cap)
			{
				break;
			}
			if (cur == goal)
			{
				List<double[]> pts = new ArrayList<>();
				int k = cur;
				while (k != -1)
				{
					pts.add(cellCenter(k % GW, k / GW));
					k = came[k];
				}
				Collections.reverse(pts);
				return simplify(pts).toArray(new double[0][]);
			}
			int cx = cur % GW, cy = cur / GW;
			float cg = gScore[cur];
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int nx = cx + dx, ny = cy + dy;
					if (!inGrid(nx, ny) || !isWaterCell(nx, ny))
					{
						continue;
					}
					if (dx != 0 && dy != 0 && (!isWaterCell(cx + dx, cy) || !isWaterCell(cx, cy + dy)))
					{
						continue;   // no corner cutting
					}
					int ni = cellIdx(nx, ny);
					float step = (dx != 0 && dy != 0) ? 1.4142f : 1f;
					float ng = cg + step;
					if (seen[ni] != s || ng < gScore[ni])
					{
						seen[ni] = s;
						gScore[ni] = ng;
						came[ni] = cur;
						heap.add(new double[]{ng + h(ni, bx, by), ni});
					}
				}
			}
		}
		return null;
	}

	private double h(int i, int bx, int by)
	{
		return Math.hypot((i % GW) - bx, (i / GW) - by);
	}

	private List<double[]> simplify(List<double[]> pts)
	{
		if (pts.size() < 3)
		{
			return pts;
		}
		List<double[]> out = new ArrayList<>();
		out.add(pts.get(0));
		for (int i = 1; i < pts.size() - 1; i++)
		{
			double[] a = out.get(out.size() - 1), b = pts.get(i), c = pts.get(i + 1);
			double cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
			if (Math.abs(cross) > CELL * CELL * 0.5)
			{
				out.add(b);
			}
		}
		out.add(pts.get(pts.size() - 1));
		return out;
	}

	private boolean losWater(double ax, double ay, double bx, double by)
	{
		int steps = (int) Math.max(2, Math.ceil(Math.hypot(bx - ax, by - ay) / (CELL * 0.5)));
		for (int i = 1; i < steps; i++)
		{
			double x = ax + (bx - ax) * i / steps, y = ay + (by - ay) * i / steps;
			int[] c = toCell(x, y);
			if (!isWaterCell(c[0], c[1]))
			{
				return false;
			}
		}
		return true;
	}

	private List<double[]> stringPull(List<double[]> pts)
	{
		if (pts.size() <= 2)
		{
			return pts;
		}
		List<double[]> out = new ArrayList<>();
		out.add(pts.get(0));
		int a = 0;
		while (a < pts.size() - 1)
		{
			int b = pts.size() - 1;
			while (b > a + 1 && !losWater(pts.get(a)[0], pts.get(a)[1], pts.get(b)[0], pts.get(b)[1]))
			{
				b--;
			}
			out.add(pts.get(b));
			a = b;
		}
		return out;
	}

	private List<double[]> chaikin(List<double[]> pts, int iters)
	{
		for (int k = 0; k < iters && pts.size() > 2; k++)
		{
			List<double[]> out = new ArrayList<>();
			out.add(pts.get(0));
			for (int i = 0; i < pts.size() - 1; i++)
			{
				double[] p = pts.get(i), q = pts.get(i + 1);
				out.add(new double[]{p[0] * 0.75 + q[0] * 0.25, p[1] * 0.75 + q[1] * 0.25});
				out.add(new double[]{p[0] * 0.25 + q[0] * 0.75, p[1] * 0.25 + q[1] * 0.75});
			}
			out.add(pts.get(pts.size() - 1));
			pts = out;
		}
		return pts;
	}

	/** Smoothed water route between two game points, or null if they're not connected by navigable water. */
	double[][] waterPath(double ax, double ay, double bx, double by)
	{
		String k = ax + "," + ay + ">" + bx + "," + by;
		if (pathCache.containsKey(k))
		{
			return pathCache.get(k);
		}
		double[][] v = computeWaterPath(ax, ay, bx, by);
		pathCache.put(k, v);
		return v;
	}

	private double[][] computeWaterPath(double ax, double ay, double bx, double by)
	{
		double d = Math.hypot(bx - ax, by - ay);
		if (d <= 24)
		{
			return new double[][]{{ax, ay}, {bx, by}};
		}
		int[] sa = snapWater(ax, ay), sb = snapWater(bx, by);
		if (sa == null || sb == null)
		{
			return null;
		}
		int ca = inGrid(sa[0], sa[1]) ? comp()[sa[1] * GW + sa[0]] : -1;
		int cb = inGrid(sb[0], sb[1]) ? comp()[sb[1] * GW + sb[0]] : -1;
		if (ca >= 0 && cb >= 0 && ca != cb)
		{
			return null;
		}
		double[][] grid = astar(sa[0], sa[1], sb[0], sb[1]);
		if (grid == null)
		{
			return null;
		}
		List<double[]> path = new ArrayList<>();
		path.add(new double[]{ax, ay});
		Collections.addAll(path, grid);
		path.add(new double[]{bx, by});
		path = stringPull(path);
		if (onLand(bx, by))
		{
			int j = path.size() - 2;
			while (j > 0 && Math.hypot(path.get(j)[0] - bx, path.get(j)[1] - by) <= 12)
			{
				j--;
			}
			List<double[]> np = new ArrayList<>(path.subList(0, j + 1));
			np.add(new double[]{bx, by});
			path = np;
		}
		if (onLand(ax, ay))
		{
			int i = 1;
			while (i < path.size() - 1 && Math.hypot(path.get(i)[0] - ax, path.get(i)[1] - ay) <= 12)
			{
				i++;
			}
			List<double[]> np = new ArrayList<>();
			np.add(new double[]{ax, ay});
			np.addAll(path.subList(i, path.size()));
			path = np;
		}
		path = chaikin(path, 2);
		return path.toArray(new double[0][]);
	}

	private boolean onLand(double x, double y)
	{
		int[] c = toCell(x, y);
		return !isWaterCell(c[0], c[1]);
	}

	private int[] comp()
	{
		if (comp == null)
		{
			labelComponents();
		}
		return comp;
	}

	private void labelComponents()
	{
		int n = GW * GH;
		int[] c = new int[n];
		Arrays.fill(c, -1);
		int[] stack = new int[n];
		int cid = 0;
		for (int s = 0; s < n; s++)
		{
			if (c[s] != -1)
			{
				continue;
			}
			int sx = s % GW, sy = s / GW;
			if (!isWaterCell(sx, sy))
			{
				continue;
			}
			c[s] = cid;
			int sp = 0;
			stack[sp++] = s;
			while (sp > 0)
			{
				int cur = stack[--sp], cx = cur % GW, cy = cur / GW;
				for (int dx = -1; dx <= 1; dx++)
				{
					for (int dy = -1; dy <= 1; dy++)
					{
						if (dx == 0 && dy == 0)
						{
							continue;
						}
						int nx = cx + dx, ny = cy + dy;
						if (nx < 0 || ny < 0 || nx >= GW || ny >= GH)
						{
							continue;
						}
						int ni = ny * GW + nx;
						if (c[ni] == -1 && isWaterCell(nx, ny))
						{
							c[ni] = cid;
							stack[sp++] = ni;
						}
					}
				}
			}
			cid++;
		}
		comp = c;
	}

	private int taskComp(ChartingTask t)
	{
		int[] s = snapWater(t.x, t.y);
		return s != null ? comp()[s[1] * GW + s[0]] : -2;
	}

	// ---- task geometry ----
	private static double[] entry(ChartingTask t)
	{
		return new double[]{t.x, t.y};
	}

	private static double[] exitPt(ChartingTask t)
	{
		return (t.isDrift() && t.type != WEATHER) ? new double[]{t.t8x, t.t8y} : new double[]{t.x, t.y};
	}

	private static String driftKind(ChartingTask t)
	{
		return t.type == WEATHER ? "weather" : "duck";
	}

	private static double segProjT(double px, double py, double ax, double ay, double bx, double by)
	{
		double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
		return l2 != 0 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0;
	}

	private static double segProjD(double px, double py, double ax, double ay, double bx, double by)
	{
		double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
		double t = l2 != 0 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0;
		double tc = Math.max(0, Math.min(1, t));
		return Math.hypot(px - (ax + tc * dx), py - (ay + tc * dy));
	}

	// ---- tour ordering ----
	private static double tourCost(List<ChartingTask> ord, double sx, double sy)
	{
		double d = 0, cx = sx, cy = sy;
		for (ChartingTask t : ord)
		{
			double[] e = entry(t);
			d += Math.hypot(e[0] - cx, e[1] - cy);
			double[] x = exitPt(t);
			cx = x[0];
			cy = x[1];
		}
		return d;
	}

	private static List<ChartingTask> nnOrder(List<ChartingTask> tasks, double sx, double sy)
	{
		List<ChartingTask> left = new ArrayList<>(tasks), out = new ArrayList<>();
		double cx = sx, cy = sy;
		while (!left.isEmpty())
		{
			int bi = 0;
			double bd = Double.MAX_VALUE;
			for (int i = 0; i < left.size(); i++)
			{
				double[] e = entry(left.get(i));
				double d = Math.hypot(e[0] - cx, e[1] - cy);
				if (d < bd)
				{
					bd = d;
					bi = i;
				}
			}
			ChartingTask t = left.remove(bi);
			out.add(t);
			double[] x = exitPt(t);
			cx = x[0];
			cy = x[1];
		}
		return out;
	}

	private static List<ChartingTask> twoOpt(List<ChartingTask> ord, double sx, double sy)
	{
		if (ord.size() < 4)
		{
			return ord;
		}
		List<ChartingTask> best = new ArrayList<>(ord);
		double bestC = tourCost(best, sx, sy);
		boolean improved = true;
		int guard = 0, n = best.size();
		while (improved && guard++ < 30)
		{
			improved = false;
			for (int i = 0; i < n - 1; i++)
			{
				for (int j = i + 1; j < n; j++)
				{
					List<ChartingTask> cand = new ArrayList<>(best.subList(0, i));
					List<ChartingTask> mid = new ArrayList<>(best.subList(i, j + 1));
					Collections.reverse(mid);
					cand.addAll(mid);
					cand.addAll(best.subList(j + 1, n));
					double c = tourCost(cand, sx, sy);
					if (c < bestC - 1e-6)
					{
						best = cand;
						bestC = c;
						improved = true;
					}
				}
			}
		}
		return best;
	}

	// ---- route ----
	static final class Leg
	{
		final String kind;   // "hop", "travel", "duck", "weather"
		final int ocean;
		final double fx, fy, tx, ty;
		final int toId;
		final boolean done;
		boolean bow;

		Leg(String kind, int ocean, double fx, double fy, double tx, double ty, int toId, boolean done)
		{
			this.kind = kind;
			this.ocean = ocean;
			this.fx = fx;
			this.fy = fy;
			this.tx = tx;
			this.ty = ty;
			this.toId = toId;
			this.done = done;
		}
	}

	static final class RouteResult
	{
		final List<Leg> legs = new ArrayList<>();
		final Map<Integer, Integer> order = new HashMap<>();   // task id -> sequence number
	}

	/**
	 * @param pts  tasks to route (already filtered by level/done/etc.)
	 * @param mode "fast" (group by sea) or "ocean" (clear a whole ocean before the next)
	 * @param sx,sy start position (the player's boat)
	 * @param done  completed task ids (legs to those are drawn dimmed)
	 */
	RouteResult buildRoute(List<ChartingTask> pts, String mode, double sx, double sy, Set<Integer> done)
	{
		boolean byOcean = "ocean".equals(mode);
		Map<String, List<ChartingTask>> bySea = new LinkedHashMap<>();
		Map<String, Integer> groupOcean = new HashMap<>();
		for (ChartingTask p : pts)
		{
			String k = byOcean ? (p.ocean + "|" + p.sea) : String.valueOf(p.sea);
			bySea.computeIfAbsent(k, x -> new ArrayList<>()).add(p);
			groupOcean.putIfAbsent(k, p.ocean);
		}

		RouteResult out = new RouteResult();
		int[] seq = {0};
		double cx = sx, cy = sy;
		Set<String> rem = new java.util.LinkedHashSet<>(bySea.keySet());
		Integer activeOcean = null;

		while (!rem.isEmpty())
		{
			Set<String> cand = rem;
			if (byOcean)
			{
				boolean activeHasRemaining = activeOcean != null;
				if (activeHasRemaining)
				{
					boolean any = false;
					for (String k : rem)
					{
						if (groupOcean.get(k).equals(activeOcean))
						{
							any = true;
							break;
						}
					}
					activeHasRemaining = any;
				}
				if (!activeHasRemaining)
				{
					String nk = null;
					double nd = Double.MAX_VALUE;
					for (String k : rem)
					{
						for (ChartingTask p : bySea.get(k))
						{
							double[] e = entry(p);
							double dd = Math.hypot(e[0] - cx, e[1] - cy);
							if (dd < nd)
							{
								nd = dd;
								nk = k;
							}
						}
					}
					activeOcean = groupOcean.get(nk);
				}
				cand = new java.util.LinkedHashSet<>();
				for (String k : rem)
				{
					if (groupOcean.get(k).equals(activeOcean))
					{
						cand.add(k);
					}
				}
			}

			String bk = null;
			double bd = Double.MAX_VALUE;
			for (String k : cand)
			{
				for (ChartingTask p : bySea.get(k))
				{
					double[] e = entry(p);
					double dd = Math.hypot(e[0] - cx, e[1] - cy);
					if (dd < bd)
					{
						bd = dd;
						bk = k;
					}
				}
			}

			// split the chosen sea into water components; route the biggest, append the rest
			Map<Integer, List<ChartingTask>> groups = new LinkedHashMap<>();
			for (ChartingTask t : bySea.get(bk))
			{
				groups.computeIfAbsent(taskComp(t), x -> new ArrayList<>()).add(t);
			}
			int mainC = 0, mainN = -1;
			for (Map.Entry<Integer, List<ChartingTask>> e : groups.entrySet())
			{
				if (e.getValue().size() > mainN)
				{
					mainN = e.getValue().size();
					mainC = e.getKey();
				}
			}
			List<ChartingTask> ord = twoOpt(nnOrder(groups.get(mainC), cx, cy), cx, cy);
			List<ChartingTask> extra = new ArrayList<>();
			for (Map.Entry<Integer, List<ChartingTask>> e : groups.entrySet())
			{
				if (e.getKey() != mainC)
				{
					extra.addAll(e.getValue());
				}
			}
			if (!extra.isEmpty())
			{
				double[] e = exitPt(ord.get(ord.size() - 1));
				ord = new ArrayList<>(ord);
				ord.addAll(nnOrder(extra, e[0], e[1]));
			}

			Set<Integer> absorbed = new java.util.HashSet<>();
			boolean first = true;
			for (int idx = 0; idx < ord.size(); idx++)
			{
				ChartingTask t = ord.get(idx);
				if (absorbed.contains(t.id))
				{
					continue;
				}
				out.order.put(t.id, ++seq[0]);
				int oi = t.ocean;
				double[] e = entry(t);
				boolean tDone = done.contains(t.id);
				out.legs.add(new Leg(first ? "hop" : "travel", oi, cx, cy, e[0], e[1], t.id, tDone));
				first = false;

				if (t.isDrift())
				{
					double sX = e[0], sY = e[1];
					double enX = t.t8x, enY = t.t8y;
					String dk = driftKind(t);
					List<double[]> cor = new ArrayList<>();   // {qx, qy, projT, id}
					for (int k = idx + 1; k < ord.size(); k++)
					{
						ChartingTask q = ord.get(k);
						if (absorbed.contains(q.id) || q.isDrift() || taskComp(q) != taskComp(t))
						{
							continue;
						}
						double[] qe = entry(q);
						double projT = segProjT(qe[0], qe[1], sX, sY, enX, enY);
						double projD = segProjD(qe[0], qe[1], sX, sY, enX, enY);
						if (projD <= CORRIDOR && projT > 0.02 && projT < 0.98)
						{
							cor.add(new double[]{qe[0], qe[1], projT, q.id});
							absorbed.add(q.id);
						}
					}
					cor.sort((a, b) -> Double.compare(a[2], b[2]));
					double px = sX, py = sY;
					for (double[] c : cor)
					{
						int qid = (int) c[3];
						out.order.put(qid, ++seq[0]);
						out.legs.add(new Leg(dk, oi, px, py, c[0], c[1], qid, done.contains(qid)));
						px = c[0];
						py = c[1];
					}
					out.legs.add(new Leg(dk, oi, px, py, enX, enY, t.id, tDone));
					if (t.type == WEATHER)
					{
						out.legs.add(new Leg(dk, oi, enX, enY, sX, sY, t.id, tDone));   // weather round-trip back to the start
						cx = sX;
						cy = sY;
					}
					else
					{
						cx = enX;
						cy = enY;   // a duck ends at its drift-exit
					}
				}
				else
				{
					cx = e[0];
					cy = e[1];
				}
			}
			rem.remove(bk);
		}

		// flag there-and-back leg pairs so the overlay can bow them apart instead of overlapping
		for (int i = 0; i < out.legs.size() - 1; i++)
		{
			Leg a = out.legs.get(i), b = out.legs.get(i + 1);
			if (Math.abs(a.tx - b.fx) >= 1 || Math.abs(a.ty - b.fy) >= 1)
			{
				continue;
			}
			double ang = Math.abs(Math.atan2(a.ty - a.fy, a.tx - a.fx) - Math.atan2(b.ty - b.fy, b.tx - b.fx));
			if (ang > Math.PI)
			{
				ang = 2 * Math.PI - ang;
			}
			if (ang <= 2.7)
			{
				continue;
			}
			double dx = a.tx - a.fx, dy = a.ty - a.fy, l2 = dx * dx + dy * dy == 0 ? 1 : dx * dx + dy * dy;
			double tt = ((b.tx - a.fx) * dx + (b.ty - a.fy) * dy) / l2;
			double perp = Math.abs((b.tx - a.fx) * dy - (b.ty - a.fy) * dx) / Math.sqrt(l2);
			if (tt > -0.05 && tt < 0.95 && perp < 15)
			{
				a.bow = true;
				b.bow = true;
			}
		}
		return out;
	}
}
