package com.seacharting;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the route on the ground (in-world) and on the minimap, plus task icons on the ground, so you
 *  can follow it while sailing without opening the full map. */
public class SeaChartingSceneOverlay extends Overlay
{
	private static final Color WATER_COLOR = new Color(0x19, 0xC8, 0xE6);
	private static final Color RELOCATE_COLOR = new Color(0xC8, 0xC8, 0xC8, 170);
	private static final Color DUCK_COLOR = new Color(0x4C, 0xD9, 0x6F);
	private static final Color WEATHER_COLOR = new Color(0x9B, 0x5C, 0xF6);

	/** a task icon to draw on the ground at a world tile. */
	static final class GroundIcon
	{
		final int x, y;
		final BufferedImage img;

		GroundIcon(int x, int y, BufferedImage img)
		{
			this.x = x;
			this.y = y;
			this.img = img;
		}
	}

	private final Client client;
	private volatile List<SeaChartingWorldMapOverlay.Seg> segs;
	private volatile List<GroundIcon> icons;

	@Inject
	SeaChartingSceneOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	void setSegs(List<SeaChartingWorldMapOverlay.Seg> segs)
	{
		this.segs = segs;
	}

	void setIcons(List<GroundIcon> icons)
	{
		this.icons = icons;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (client.getLocalPlayer() == null)
		{
			return null;
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		List<SeaChartingWorldMapOverlay.Seg> ss = segs;
		if (ss != null)
		{
			for (SeaChartingWorldMapOverlay.Seg s : ss)
			{
				if (s.poly == null || s.poly.length < 2)
				{
					continue;
				}
				g.setColor(colorFor(s));
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				drawPolyline(g, s.poly, false);   // on the ground
				g.setStroke(new BasicStroke(2f));
				drawPolyline(g, s.poly, true);    // on the minimap
			}
		}

		List<GroundIcon> is = icons;
		if (is != null)
		{
			for (GroundIcon gi : is)
			{
				if (gi.img == null)
				{
					continue;
				}
				Point p = toCanvas(gi.x, gi.y);
				if (p != null)
				{
					g.drawImage(gi.img, p.getX() - gi.img.getWidth() / 2, p.getY() - gi.img.getHeight() / 2, null);
				}
			}
		}
		return null;
	}

	private void drawPolyline(Graphics2D g, double[][] poly, boolean minimap)
	{
		Point prev = null;
		for (double[] pt : poly)
		{
			Point cur = minimap ? toMinimap((int) Math.round(pt[0]), (int) Math.round(pt[1]))
				: toCanvas((int) Math.round(pt[0]), (int) Math.round(pt[1]));
			if (cur != null && prev != null)
			{
				g.drawLine(prev.getX(), prev.getY(), cur.getX(), cur.getY());
			}
			prev = cur;
		}
	}

	private static Color colorFor(SeaChartingWorldMapOverlay.Seg s)
	{
		if (s.style == SeaChartingWorldMapOverlay.STYLE_RELOCATE)
		{
			return RELOCATE_COLOR;
		}
		if (s.style == SeaChartingWorldMapOverlay.STYLE_DRIFT)
		{
			return s.weather ? WEATHER_COLOR : DUCK_COLOR;
		}
		return WATER_COLOR;
	}

	private LocalPoint toLocal(int worldX, int worldY)
	{
		return LocalPoint.fromWorld(client, new WorldPoint(worldX, worldY, client.getPlane()));
	}

	private Point toMinimap(int worldX, int worldY)
	{
		LocalPoint lp = toLocal(worldX, worldY);
		return lp == null ? null : Perspective.localToMinimap(client, lp);
	}

	private Point toCanvas(int worldX, int worldY)
	{
		LocalPoint lp = toLocal(worldX, worldY);
		return lp == null ? null : Perspective.localToCanvas(client, lp, client.getPlane());
	}
}
