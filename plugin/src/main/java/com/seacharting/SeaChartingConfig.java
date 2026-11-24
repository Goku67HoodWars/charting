package com.seacharting;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(SeaChartingConfig.GROUP)
public interface SeaChartingConfig extends Config
{
	String GROUP = "seacharting";

	@ConfigItem(
		keyName = "showWorldMapMarkers",
		name = "World map markers",
		description = "Show every charting task as a color-coded dot on the world map",
		position = 0
	)
	default boolean showWorldMapMarkers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Info overlay",
		description = "Show the task count and nearest-task box",
		position = 1
	)
	default boolean showPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideAboveLevel",
		name = "Only show tasks I can do",
		description = "Hide charting tasks above your effective Sailing level",
		position = 2
	)
	default boolean hideAboveLevel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "levelOverride",
		name = "Sailing level override",
		description = "0 = use your in-game level. Set a value to force it, e.g. when a boost lets you chart above your base level.",
		position = 3
	)
	default int levelOverride()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showCompleted",
		name = "Show completed tasks",
		description = "Show tasks you've already charted (default hides them)",
		position = 4
	)
	default boolean showCompleted()
	{
		return false;
	}

	@ConfigItem(
		keyName = "logVarbits",
		name = "Debug: log var changes",
		description = "Log varbit/varp changes to the console, to find the charting-completion vars",
		position = 5
	)
	default boolean logVarbits()
	{
		return false;
	}
}
