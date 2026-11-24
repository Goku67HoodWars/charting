package com.seacharting;

import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * v1 proof-of-pipeline HUD: confirms the data loaded and the plugin can read the player's live
 * position by naming the nearest task. Replaced/extended by the route HUD once the router is ported.
 */
public class SeaChartingOverlay extends OverlayPanel
{
	private final Client client;
	private final SeaChartingConfig config;
	private ChartingData data;

	@Inject
	SeaChartingOverlay(Client client, SeaChartingConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	void setData(ChartingData data)
	{
		this.data = data;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (data == null || !config.showPanel())
		{
			return null;
		}

		panelComponent.getChildren().add(TitleComponent.builder().text("Sea Charting").build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Tasks").right(String.valueOf(data.tasks.size())).build());

		if (client.getLocalPlayer() != null)
		{
			WorldPoint me = client.getLocalPlayer().getWorldLocation();
			ChartingTask nearest = null;
			long best = Long.MAX_VALUE;
			for (ChartingTask t : data.tasks)
			{
				long dx = t.x - me.getX(), dy = t.y - me.getY();
				long d = dx * dx + dy * dy;
				if (d < best)
				{
					best = d;
					nearest = t;
				}
			}
			if (nearest != null)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("Nearest").right(nearest.name).build());
				panelComponent.getChildren().add(LineComponent.builder()
					.left(data.oceanName(nearest.ocean)).right((long) Math.sqrt(best) + " tiles").build());
			}
		}

		return super.render(graphics);
	}
}
