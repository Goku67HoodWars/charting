package com.seacharting;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws the planned route on the world map: water hops bold + arrowed, open-ocean hops faint dashed,
 *  duck/weather drifts bowed into a loop with a direction arrow. */
public class SeaChartingWorldMapOverlay extends Overlay
{
	static final int STYLE_WATER = 0, STYLE_RELOCATE = 1, STYLE_DRIFT = 2;
	private static final Color WATER_COLOR = new Color(0x19, 0xC8, 0xE6);
	private static final Color RELOCATE_COLOR = new Color(0xC8, 0xC8, 0xC8, 150);
	private static final Color DUCK_COLOR = new Color(0x4C, 0xD9, 0x6F);   // green: distinct from the water route's cyan
	private static final Color WEATHER_COLOR = new Color(0x9B, 0x5C, 0xF6);
	private static final Stroke SOLID = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final Stroke DASHED = new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 5f}, 0f);

	/** one drawn segment: a polyline (game coords), a style, and whether it's a weather drift. */
	static final class Seg
	{
		final double[][] poly;
		final int style;
		final boolean weather;

		Seg(double[][] poly, int style, boolean weather)
		{
			this.poly = poly;
			this.style = style;
			this.weather = weather;
		}
	}

	private final Client client;
	private volatile List<Seg> segs;

	@Inject
	SeaChartingWorldMapOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(Overlay.PRIORITY_LOW);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
	}

	void setSegs(List<Seg> segs)
	{
		this.segs = segs;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<Seg> ss = segs;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (ss == null || ss.isEmpty() || map == null)
		{
			return null;
		}
		Rectangle view = map.getBounds();
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Area clip = new Area(view);
			Widget overview = client.getWidget(InterfaceID.Worldmap.OVERVIEW_CONTAINER);
			if (overview != null && !overview.isHidden())
			{
				clip.subtract(new Area(overview.getBounds()));
			}
			g.setClip(clip);
			for (Seg s : ss)
			{
				if (s.poly == null || s.poly.length < 2)
				{
					continue;
				}
				java.awt.Point[] px = new java.awt.Point[s.poly.length];
				for (int i = 0; i < s.poly.length; i++)
				{
					px[i] = worldToMap((int) Math.round(s.poly[i][0]), (int) Math.round(s.poly[i][1]), view);
				}
				Color col;
				boolean arrow;
				if (s.style == STYLE_RELOCATE)
				{
					col = RELOCATE_COLOR;
					g.setStroke(DASHED);
					arrow = false;
				}
				else if (s.style == STYLE_DRIFT)
				{
					col = s.weather ? WEATHER_COLOR : DUCK_COLOR;
					g.setStroke(SOLID);
					arrow = true;
				}
				else
				{
					col = WATER_COLOR;
					g.setStroke(SOLID);
					arrow = true;
				}
				g.setColor(col);
				for (int i = 0; i + 1 < px.length; i++)
				{
					if (px[i] != null && px[i + 1] != null)
					{
						g.drawLine(px[i].x, px[i].y, px[i + 1].x, px[i + 1].y);
					}
				}
				if (arrow)
				{
					drawArrow(g, px);
				}
			}
		}
		finally
		{
			g.dispose();
		}
		return null;
	}

	/** A small chevron at the segment's midpoint pointing along travel direction. */
	private void drawArrow(Graphics2D g, java.awt.Point[] px)
	{
		int target = px.length / 2;
		int best = -1;
		for (int i = 0; i + 1 < px.length; i++)
		{
			if (px[i] != null && px[i + 1] != null && (best < 0 || Math.abs(i - target) < Math.abs(best - target)))
			{
				best = i;
			}
		}
		if (best < 0)
		{
			return;
		}
		java.awt.Point a = px[best], b = px[best + 1];
		double ang = Math.atan2(b.y - a.y, b.x - a.x);
		double mx = (a.x + b.x) / 2.0, my = (a.y + b.y) / 2.0;
		int len = 8;
		g.drawLine((int) mx, (int) my, (int) (mx - len * Math.cos(ang - 0.5)), (int) (my - len * Math.sin(ang - 0.5)));
		g.drawLine((int) mx, (int) my, (int) (mx - len * Math.cos(ang + 0.5)), (int) (my - len * Math.sin(ang + 0.5)));
	}

	/** Project a game tile to a pixel on the world map (same math the Shortest Path plugin uses). */
	private java.awt.Point worldToMap(int worldX, int worldY, Rectangle rect)
	{
		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null)
		{
			return null;
		}
		float pixelsPerTile = worldMap.getWorldMapZoom();
		net.runelite.api.Point pos = worldMap.getWorldMapPosition();

		int widthInTiles = (int) Math.ceil(rect.getWidth() / pixelsPerTile);
		int xTileOffset = worldX + widthInTiles / 2 - pos.getX();
		int x = (int) (xTileOffset * pixelsPerTile);
		x += (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
		x += (int) rect.getX();

		int heightInTiles = (int) Math.ceil(rect.getHeight() / pixelsPerTile);
		int yTileMax = pos.getY() - heightInTiles / 2;
		int yTileOffset = (yTileMax - worldY - 1) * -1;
		int y = (int) (yTileOffset * pixelsPerTile);
		y -= (int) (pixelsPerTile - Math.ceil(pixelsPerTile / 2));
		y = rect.height - y;
		y += (int) rect.getY();

		return new java.awt.Point(x, y);
	}
}
