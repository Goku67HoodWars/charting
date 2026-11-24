package com.seacharting;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Local launcher: starts a developer-mode RuneLite client with this plugin loaded.
 * Run this main (IntelliJ: right-click > Run, or `gradlew run`).
 */
public class SeaChartingPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SeaChartingPlugin.class);
		RuneLite.main(args);
	}
}
