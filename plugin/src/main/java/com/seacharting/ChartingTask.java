package com.seacharting;

/**
 * One sea-charting task. Field order mirrors the web app's packed task array:
 * [id, name, type, x, y, level, sea, ocean, t8].
 * Type codes: 0 GENERIC, 1 SPYGLASS, 2 CURRENT_DUCK, 3 DRINK_CRATE, 4 MERMAID_GUIDE, 5 WEATHER.
 */
public class ChartingTask
{
	public static final int CURRENT_DUCK = 2;
	public static final int WEATHER = 5;

	public final int id;
	public final String name;
	public final int type;
	public final int x, y;
	public final int level;
	public final int sea, ocean;
	/** Second point for drift tasks (duck drift-exit, or weather reading point); null otherwise. */
	public final Integer t8x, t8y;

	public ChartingTask(int id, String name, int type, int x, int y, int level, int sea, int ocean, Integer t8x, Integer t8y)
	{
		this.id = id;
		this.name = name;
		this.type = type;
		this.x = x;
		this.y = y;
		this.level = level;
		this.sea = sea;
		this.ocean = ocean;
		this.t8x = t8x;
		this.t8y = t8y;
	}

	/** Duck/weather tasks that carry a second point: drawn as a drift (duck one-way, weather round-trip). */
	public boolean isDrift()
	{
		return (type == CURRENT_DUCK || type == WEATHER) && t8x != null;
	}
}
