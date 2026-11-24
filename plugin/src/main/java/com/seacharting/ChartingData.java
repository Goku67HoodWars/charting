package com.seacharting;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the charting dataset bundled from the web app (resources/charting/tasks.json), the single
 * source of truth for the 358 tasks, their seas and oceans. Regenerate with tools/extract-data.py
 * whenever the web app's data changes.
 */
public class ChartingData
{
	public final List<String> types = new ArrayList<>();
	public final List<String> seas = new ArrayList<>();
	public final List<String> oceans = new ArrayList<>();
	public final List<ChartingTask> tasks = new ArrayList<>();
	private final Map<Integer, ChartingTask> byId = new HashMap<>();

	// ocean palette mirrors the web app order: ardent, sunset, unquiet, misc, western, shrouded, northern
	private static final Color[] OCEAN_COLORS = {
		new Color(0xd0, 0x7b, 0x3a), new Color(0xd8, 0xa2, 0x1e), new Color(0x3f, 0xa0, 0x8c),
		new Color(0x9b, 0x7b, 0xb5), new Color(0x5f, 0x94, 0x40), new Color(0xc2, 0x4b, 0x86),
		new Color(0x5b, 0x9b, 0xd5)
	};
	private final Map<Integer, BufferedImage> iconCache = new HashMap<>();

	public static ChartingData load(Gson gson)
	{
		try (InputStream in = ChartingData.class.getResourceAsStream("/charting/tasks.json"))
		{
			if (in == null)
			{
				throw new IllegalStateException("charting/tasks.json not found on the classpath");
			}
			JsonObject root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			ChartingData d = new ChartingData();
			for (JsonElement e : root.getAsJsonArray("types"))
			{
				d.types.add(e.getAsString());
			}
			for (JsonElement e : root.getAsJsonArray("seas"))
			{
				d.seas.add(e.getAsString());
			}
			for (JsonElement e : root.getAsJsonArray("oceans"))
			{
				// each ocean is [key, displayName, ...stats]
				d.oceans.add(e.getAsJsonArray().get(1).getAsString());
			}
			for (JsonElement e : root.getAsJsonArray("tasks"))
			{
				JsonArray a = e.getAsJsonArray();
				Integer t8x = null, t8y = null;
				if (a.size() > 8 && a.get(8).isJsonArray())
				{
					JsonArray p = a.get(8).getAsJsonArray();
					t8x = p.get(0).getAsInt();
					t8y = p.get(1).getAsInt();
				}
				d.tasks.add(new ChartingTask(
					a.get(0).getAsInt(), a.get(1).getAsString(), a.get(2).getAsInt(),
					a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt(),
					a.get(6).getAsInt(), a.get(7).getAsInt(), t8x, t8y));
			}
			return d;
		}
		catch (Exception ex)
		{
			throw new RuntimeException("Failed to load charting data", ex);
		}
	}

	public ChartingTask byId(int id)
	{
		if (byId.isEmpty())
		{
			for (ChartingTask t : tasks)
			{
				byId.put(t.id, t);
			}
		}
		return byId.get(id);
	}

	public String seaName(int i)
	{
		return i >= 0 && i < seas.size() ? seas.get(i) : "?";
	}

	public String oceanName(int i)
	{
		return i >= 0 && i < oceans.size() ? oceans.get(i) : "?";
	}

	public String typeName(int i)
	{
		return i >= 0 && i < types.size() ? types.get(i).toLowerCase().replace('_', ' ') : "?";
	}

	public Color oceanColor(int i)
	{
		return i >= 0 && i < OCEAN_COLORS.length ? OCEAN_COLORS[i] : Color.LIGHT_GRAY;
	}

	/** A small color-coded dot keyed by ocean, drawn once and cached (no icon files to bundle). */
	// type index -> icon resource name (mirrors the web app's ICON_KEY)
	private static final String[] ICON_FILE = {"generic", "spyglass", "current", "crate", "diving", "weather"};
	private BufferedImage[] typeIcons;

	/** The task's type icon inside a ring in its ocean's color — keeps both the glyph and the color code. */
	public BufferedImage iconFor(ChartingTask t)
	{
		return iconCache.computeIfAbsent(t.type * 100 + t.ocean, k -> compose(t.type, t.ocean));
	}

	private BufferedImage typeIcon(int type)
	{
		if (typeIcons == null)
		{
			typeIcons = new BufferedImage[ICON_FILE.length];
		}
		if (type < 0 || type >= ICON_FILE.length)
		{
			return null;
		}
		if (typeIcons[type] == null)
		{
			try (InputStream in = ChartingData.class.getResourceAsStream("/charting/icons/" + ICON_FILE[type] + ".png"))
			{
				if (in != null)
				{
					typeIcons[type] = ImageIO.read(in);
				}
			}
			catch (Exception ignored)
			{
				// fall back to a plain dot below
			}
		}
		return typeIcons[type];
	}

	private BufferedImage compose(int type, int ocean)
	{
		BufferedImage icon = typeIcon(type);
		Color ring = oceanColor(ocean);
		if (icon == null)
		{
			return dot(ring);
		}
		int glyph = 16;   // scale the type icon down to a small map-marker size
		int iw = icon.getWidth(), ih = icon.getHeight();
		double scale = (double) glyph / Math.max(iw, ih);
		int sw = Math.max(1, (int) Math.round(iw * scale)), sh = Math.max(1, (int) Math.round(ih * scale));
		int d = glyph + 7;
		BufferedImage out = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0, 0, 0, 150));
		g.drawOval(1, 1, d - 3, d - 3);
		g.setStroke(new BasicStroke(2f));
		g.setColor(ring);
		g.drawOval(2, 2, d - 5, d - 5);
		g.drawImage(icon, (d - sw) / 2, (d - sh) / 2, sw, sh, null);
		g.dispose();
		return out;
	}

	private static BufferedImage dot(Color c)
	{
		int s = 11;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0, 0, 0, 160));
		g.fillOval(0, 0, s, s);
		g.setColor(c);
		g.fillOval(1, 1, s - 2, s - 2);
		g.dispose();
		return img;
	}
}
