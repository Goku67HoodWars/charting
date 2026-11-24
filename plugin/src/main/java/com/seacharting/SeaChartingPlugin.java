package com.seacharting;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyDataService;
import net.runelite.client.party.PartyDataType;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.PartyData;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Sea Charting Route",
	description = "Plans and overlays OSRS Sailing sea-charting routes",
	tags = {"sailing", "sea", "charting", "route", "boat"}
)
public class SeaChartingPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private SeaChartingConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WorldMapPointManager worldMapPointManager;

	@Inject
	private SeaChartingOverlay overlay;

	@Inject
	private SeaChartingWorldMapOverlay mapOverlay;

	@Inject
	private SeaChartingSceneOverlay sceneOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SeaChartingPanel panel;

	@Inject
	private PartyDataService partyDataService;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	@Inject
	private Gson gson;

	private static final int CHARTING_VARBIT_BASE = 18574;   // per-task completion varbit = base + task id (game cache; value 1 = done)

	private ChartingData data;
	private NavigationButton navButton;
	private final List<WorldMapPoint> mapPoints = new ArrayList<>();
	private final java.util.Set<Integer> done = new java.util.HashSet<>();
	private boolean doneDirty = false;   // a charting var changed (or we just logged in): re-read on the next settled tick
	private int lastEffectiveLevel = -1;
	private int lastRouteX = -9999, lastRouteY = -9999;
	private volatile boolean hasOwnRoute = false;   // do I have uncompleted tasks of my own to chart?
	private int tickLog = 0;
	private ChartingRouter router;
	private final java.util.concurrent.ExecutorService routeExec = java.util.concurrent.Executors.newSingleThreadExecutor();

	@Provides
	SeaChartingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SeaChartingConfig.class);
	}

	@Override
	protected void startUp()
	{
		data = ChartingData.load(gson);
		router = new ChartingRouter(gson);
		overlay.setData(data);
		overlayManager.add(overlay);
		overlayManager.add(mapOverlay);
		overlayManager.add(sceneOverlay);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/charting/icons/spyglass.png");
		navButton = NavigationButton.builder()
			.tooltip("Sea Charting")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		partyDataService.subscribe(this, PartyDataType.CHARACTER_NAME, PartyDataType.COORD);   // populate member names + positions
		wsClient.registerMessage(SeaChartingRouteMessage.class);

		refreshMarkers();
		log.info("Sea Charting loaded {} tasks across {} oceans", data.tasks.size(), data.oceans.size());
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(mapOverlay);
		overlayManager.remove(sceneOverlay);
		clientToolbar.removeNavigation(navButton);
		partyDataService.unsubscribe(this, PartyDataType.CHARACTER_NAME, PartyDataType.COORD);
		wsClient.unregisterMessage(SeaChartingRouteMessage.class);
		routeExec.shutdownNow();
		clearMapPoints();
		data = null;
	}

	/** Override wins; otherwise the in-game Sailing level (boosts included). Unknown before login = show all. */
	private int effectiveLevel()
	{
		int override = config.levelOverride();
		if (override > 0)
		{
			return override;
		}
		if (client.getLocalPlayer() == null)
		{
			return 99;
		}
		int lvl = client.getRealSkillLevel(Skill.SAILING);   // default to the real level, not boosted — user bumps it manually for boosts
		return lvl > 0 ? lvl : 99;
	}

	private void refreshMarkers()
	{
		clearMapPoints();
		if (data != null && config.showWorldMapMarkers())
		{
			addMapPoints();
		}
		else
		{
			sceneOverlay.setIcons(null);
		}
		recomputeRoute();
	}

	/** Build the route (host's, if in a party), resolve its water path off-thread, and broadcast it. */
	private void recomputeRoute()
	{
		if (router == null || data == null || client.getLocalPlayer() == null)
		{
			pushSegs(null);
			return;
		}
		int eff = effectiveLevel();
		boolean hideAbove = config.hideAboveLevel();
		final List<ChartingTask> basis = new ArrayList<>();
		for (ChartingTask t : data.tasks)
		{
			if (hideAbove && t.level > eff)
			{
				continue;
			}
			if (done.contains(t.id))
			{
				continue;   // route only over what's left
			}
			if (t.y >= 7000)
			{
				continue;   // off-map instanced task (Charting Tutor) can't be routed
			}
			basis.add(t);
		}
		WorldPoint me = client.getLocalPlayer().getWorldLocation();
		final int sx = me.getX(), sy = me.getY();
		final boolean inParty = partyService.isInParty();
		log.info("SC-ROUTE recompute own start=({},{}) inParty={} hasOwn={}", sx, sy, inParty, hasOwnRoute);
		routeExec.submit(() -> {
			try
			{
				if (basis.isEmpty())
				{
					hasOwnRoute = false;
					if (!inParty)
					{
						pushSegs(null);   // solo with nothing left to chart
					}
					return;   // in a party: keep showing a partymate's shared route
				}
				hasOwnRoute = true;
				renderRoute(basis, sx, sy);
				if (inParty)
				{
					int[] ids = new int[basis.size()];
					for (int i = 0; i < basis.size(); i++)
					{
						ids[i] = basis.get(i).id;
					}
					partyService.send(new SeaChartingRouteMessage(sx, sy, ids));   // share with the party
				}
			}
			catch (Exception e)
			{
				log.warn("Sea Charting route compute failed", e);
			}
		});
	}

	/** Hand the styled route to both the world-map and minimap overlays. */
	private void pushSegs(List<SeaChartingWorldMapOverlay.Seg> segs)
	{
		mapOverlay.setSegs(segs);
		sceneOverlay.setSegs(segs);
	}

	/** Build the route over `basis` from (sx,sy), water-resolve each leg, and style it. (On routeExec.) */
	private void renderRoute(List<ChartingTask> basis, int sx, int sy)
	{
		if (basis.isEmpty())
		{
			pushSegs(null);
			return;
		}
		ChartingRouter.RouteResult r = router.buildRoute(basis, "fast", sx, sy, java.util.Collections.emptySet());
		int cap = Math.min(20, r.legs.size());   // next ~20 hops: legible + fast
		List<SeaChartingWorldMapOverlay.Seg> segs = new ArrayList<>();
		for (int i = 0; i < cap; i++)
		{
			ChartingRouter.Leg leg = r.legs.get(i);
			boolean weather = "weather".equals(leg.kind);
			boolean drift = weather || "duck".equals(leg.kind);
			double[][] wp;
			try
			{
				wp = router.waterPath(leg.fx, leg.fy, leg.tx, leg.ty);
			}
			catch (Exception ex)
			{
				wp = null;   // one bad leg becomes a relocate hop, never aborts the whole route
			}
			boolean resolved = wp != null;
			double[][] poly = resolved ? wp : new double[][]{{leg.fx, leg.fy}, {leg.tx, leg.ty}};
			int style;
			if (drift)
			{
				poly = bulge(poly, 10);   // bow the drift so an out-and-back reads as a loop
				style = SeaChartingWorldMapOverlay.STYLE_DRIFT;
			}
			else if (resolved)
			{
				style = SeaChartingWorldMapOverlay.STYLE_WATER;
			}
			else
			{
				style = SeaChartingWorldMapOverlay.STYLE_RELOCATE;   // no water route: faint "relocate" hop
			}
			segs.add(new SeaChartingWorldMapOverlay.Seg(poly, style, weather));
		}
		pushSegs(segs);
	}

	/** Bow a leg sideways into a shallow arc so antiparallel drift legs separate into a visible loop. */
	private static double[][] bulge(double[][] poly, double dist)
	{
		double ax = poly[0][0], ay = poly[0][1], bx = poly[poly.length - 1][0], by = poly[poly.length - 1][1];
		double dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
		if (len < 1)
		{
			return poly;
		}
		double nx = -dy / len, ny = dx / len;   // left normal
		double mx = (ax + bx) / 2 + nx * dist, my = (ay + by) / 2 + ny * dist;
		return new double[][]{{ax, ay}, {mx, my}, {bx, by}};
	}

	@Subscribe
	public void onSeaChartingRouteMessage(SeaChartingRouteMessage event)
	{
		if (!partyService.isInParty() || hasOwnRoute)
		{
			return;   // I have my own tasks to chart — show my route, not a partymate's
		}
		final int sx = event.getStartX(), sy = event.getStartY();
		final int[] ids = event.getTaskIds();
		log.info("SC-ROUTE render HOST route start=({},{}) ids={}", sx, sy, ids.length);
		routeExec.submit(() -> {
			try
			{
				List<ChartingTask> basis = new ArrayList<>();
				for (int id : ids)
				{
					ChartingTask t = data.byId(id);
					if (t != null)
					{
						basis.add(t);
					}
				}
				renderRoute(basis, sx, sy);
			}
			catch (Exception e)
			{
				log.warn("Sea Charting route render failed", e);
			}
		});
	}

	private void addMapPoints()
	{
		final int eff = effectiveLevel();
		lastEffectiveLevel = eff;
		final boolean hideAbove = config.hideAboveLevel();
		List<SeaChartingSceneOverlay.GroundIcon> ground = new ArrayList<>();
		for (ChartingTask t : data.tasks)
		{
			if (hideAbove && t.level > eff)
			{
				continue;
			}
			if (!config.showCompleted() && done.contains(t.id))
			{
				continue;   // hide tasks you've already charted
			}
			ground.add(new SeaChartingSceneOverlay.GroundIcon(t.x, t.y, data.iconFor(t)));   // icon on the ground, in-world
			WorldMapPoint point = new WorldMapPoint(new WorldPoint(t.x, t.y, 0), data.iconFor(t));
			point.setName(t.name);   // required whenever jumpOnClick is set: RuneLite asserts name != null on hover (freezes the client otherwise)
			point.setTooltip(t.name + " · " + data.oceanName(t.ocean) + " › "
				+ data.seaName(t.sea) + " · " + data.typeName(t.type) + " · lvl " + t.level);
			point.setSnapToEdge(false);   // snapping 358 off-screen points to the edge every frame freezes the client
			point.setJumpOnClick(true);
			mapPoints.add(point);
			worldMapPointManager.add(point);
		}
		sceneOverlay.setIcons(ground);
	}

	private void clearMapPoints()
	{
		for (WorldMapPoint point : mapPoints)
		{
			worldMapPointManager.remove(point);
		}
		mapPoints.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (SeaChartingConfig.GROUP.equals(event.getGroup()))
		{
			refreshMarkers();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			doneDirty = true;   // read completion once vars settle (not during the login flood)
			refreshMarkers();   // level is known now
			panel.refresh();    // update the panel's level display
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() == Skill.SAILING && config.levelOverride() <= 0 && effectiveLevel() != lastEffectiveLevel)
		{
			refreshMarkers();
			panel.refresh();
		}
	}

	@Subscribe
	public void onPartyData(PartyData event)
	{
		panel.refresh();   // a member's name/position arrived — refresh the list
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (config.logVarbits())
		{
			log.info("SC-VAR varbit={} varp={} value={}", event.getVarbitId(), event.getVarpId(), event.getValue());
		}
		int id = event.getVarbitId();
		if (id >= CHARTING_VARBIT_BASE && id <= CHARTING_VARBIT_BASE + 400)
		{
			doneDirty = true;   // re-read on the next settled tick, not mid-flood
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (partyService.isInParty())
		{
			tickLog++;
			if (tickLog % 3 == 0)
			{
				panel.refresh();   // RuneLite fires no event when a member leaves/goes stale, so poll the list
			}
			if (tickLog % 10 == 0)
			{
				logParty();
			}
		}
		if (doneDirty)
		{
			doneDirty = false;
			refreshDone();
			refreshMarkers();
			panel.refresh();
			return;
		}
		// recompute as the boat moves so the route always starts from where you are now
		net.runelite.api.Player me = client.getLocalPlayer();
		if (me != null)
		{
			WorldPoint p = me.getWorldLocation();
			if (Math.abs(p.getX() - lastRouteX) + Math.abs(p.getY() - lastRouteY) > 12)
			{
				lastRouteX = p.getX();
				lastRouteY = p.getY();
				recomputeRoute();
			}
		}
	}

	private int safeVarbit(int id)
	{
		try
		{
			return client.getVarbitValue(id);
		}
		catch (Exception e)
		{
			return -999;
		}
	}

	/** Read each task's completion varbit (base + id; non-zero = done) straight from the client. */
	private void refreshDone()
	{
		done.clear();
		if (data == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		for (ChartingTask t : data.tasks)
		{
			try
			{
				if (client.getVarbitValue(CHARTING_VARBIT_BASE + t.id) != 0)
				{
					done.add(t.id);
				}
			}
			catch (Exception ignored)
			{
				// varbit id not loaded for this task — skip
			}
		}
		log.info("Sea Charting: {} of {} completed; samples v18574={} v18575={} v18616={} v18931={}",
			done.size(), data.tasks.size(), safeVarbit(18574), safeVarbit(18575), safeVarbit(18616), safeVarbit(18931));
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		log.info("SC-PARTY onPartyChanged (inParty={})", partyService.isInParty());
		logParty();
		panel.refresh();
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		log.info("SC-PARTY onUserJoin");
		logParty();
		panel.refresh();
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		log.info("SC-PARTY onUserPart");
		logParty();
		panel.refresh();
	}

	/** Dump the party's member list (id, name, loggedIn) so the leave/host behaviour is visible in the log. */
	private void logParty()
	{
		if (!partyService.isInParty())
		{
			log.info("SC-PARTY   not in a party");
			return;
		}
		PartyMember local = partyService.getLocalMember();
		StringBuilder sb = new StringBuilder("SC-PARTY   local=")
			.append(local != null ? local.getMemberId() : -1)
			.append(" count=").append(partyService.getMembers().size()).append(" -> ");
		for (PartyMember m : partyService.getMembers())
		{
			sb.append("{id=").append(m.getMemberId())
				.append(" name=").append(m.getDisplayName())
				.append(" loggedIn=").append(m.isLoggedIn()).append("} ");
		}
		log.info(sb.toString());
	}
}
