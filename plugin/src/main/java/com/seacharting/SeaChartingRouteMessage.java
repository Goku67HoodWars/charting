package com.seacharting;

import lombok.EqualsAndHashCode;
import lombok.Value;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * The host's planned route, shared to the party: the host's start tile plus the ordered task ids to
 * chart. Receivers rebuild the exact styled route locally (they have the same data + router), so the
 * helper sees where the host is headed. The class name must stay unique across all RuneLite plugins.
 */
@Value
@EqualsAndHashCode(callSuper = true)
public class SeaChartingRouteMessage extends PartyMemberMessage
{
	int startX;
	int startY;
	int[] taskIds;
}
