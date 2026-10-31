/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"detector", "vanish", "vanish detector", "gamemode spy"})
public final class DetectorHack extends Hack
	implements UpdateListener, ChatInputListener
{
	// ---------------------------------------------------------------
	// Settings
	// ---------------------------------------------------------------
	
	private final SliderSetting vanishDelay = new SliderSetting("Vanish delay",
		"How many ticks a player has to be missing from the tab list\n"
			+ "before they're flagged as vanished rather than lagging.\n"
			+ "Raise this on servers with unstable tablist updates.",
		40, 10, 200, 5, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting minPresence =
		new SliderSetting("Minimum presence",
			"How many ticks a player has to have been in the tab list\n"
				+ "before Detector will track them for vanishing at all.\n"
				+ "Filters out fake/NPC players that briefly flicker into\n"
				+ "the tab list and then disappear (a known vanilla bug on\n"
				+ "some servers), since those would otherwise get reported\n"
				+ "as \"vanished\" every time they blip out.",
			30, 0, 200, 5, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final CheckboxSetting customJoinLeaveMessages = new CheckboxSetting(
		"Use custom join/leave patterns",
		"Enable this if the server replaces the vanilla join/leave\n"
			+ "messages with its own format. Otherwise Detector only\n"
			+ "recognises the vanilla \"X joined/left the game\" messages,\n"
			+ "which means it will misreport real leaves as vanishes.",
		false);
	
	private final TextFieldSetting leavePattern = new TextFieldSetting(
		"Leave message pattern",
		"Pattern for the server's leave message. Use {player} as a\n"
			+ "placeholder for the player's name, e.g. \"[-] {player}\".\n"
			+ "Only used when the setting above is enabled.",
		"[-] {player}");
	
	private final TextFieldSetting joinPattern = new TextFieldSetting(
		"Join message pattern",
		"Pattern for the server's join message. Use {player} as a\n"
			+ "placeholder for the player's name, e.g. \"[+] {player}\".\n"
			+ "Only used when the setting above is enabled.",
		"[+] {player}");
	
	private final CheckboxSetting detectGamemodeChanges =
		new CheckboxSetting("Detect gamemode changes",
			"Announces in chat when a player's gamemode changes, read\n"
				+ "straight from the tab list's PlayerInfo updates.",
			true);
	
	// ---------------------------------------------------------------
	// Vanish / gamemode tracking state
	// ---------------------------------------------------------------
	
	private final Map<UUID, GameType> known = new HashMap<>();
	private final Map<UUID, String> nameCache = new HashMap<>();
	private final Map<UUID, Integer> firstSeen = new HashMap<>();
	private final Map<UUID, Integer> pendingRemoval = new HashMap<>();
	private final Set<UUID> vanished = new HashSet<>();
	private final Set<String> confirmedLeftByMessage = new HashSet<>();
	
	private int tickCounter;
	
	public DetectorHack()
	{
		super("Detector");
		setCategory(Category.RENDER);
		
		addSetting(vanishDelay);
		addSetting(minPresence);
		addSetting(customJoinLeaveMessages);
		addSetting(joinPattern);
		addSetting(leavePattern);
		addSetting(detectGamemodeChanges);
	}
	
	@Override
	protected void onEnable()
	{
		known.clear();
		nameCache.clear();
		firstSeen.clear();
		pendingRemoval.clear();
		vanished.clear();
		confirmedLeftByMessage.clear();
		tickCounter = 0;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
	}
	
	// =================================================================
	// 1 & 2: tablist vanish detection + gamemode change detection
	// =================================================================
	
	@Override
	public void onUpdate()
	{
		tickCounter++;
		if(MC.player == null || MC.player.connection == null)
			return;
		
		Set<UUID> currentUuids = new HashSet<>();
		
		for(PlayerInfo info : MC.player.connection.getOnlinePlayers())
		{
			UUID uuid = info.getProfile().id();
			String name = info.getProfile().name();
			GameType gm = info.getGameMode();
			currentUuids.add(uuid);
			nameCache.put(uuid, name);
			
			if(vanished.contains(uuid))
			{
				vanished.remove(uuid);
				known.put(uuid, gm);
				firstSeen.put(uuid, tickCounter);
				ChatUtils.message(name + " has unvanished.");
				continue;
			}
			
			pendingRemoval.remove(uuid);
			
			GameType previous = known.get(uuid);
			if(previous == null)
			{
				known.put(uuid, gm);
				firstSeen.put(uuid, tickCounter);
				continue;
			}
			
			if(detectGamemodeChanges.isChecked() && previous != gm)
				ChatUtils.message(name + " changed their gamemode from "
					+ formatGameMode(previous) + " to " + formatGameMode(gm)
					+ ".");
			
			known.put(uuid, gm);
		}
		
		int minTicks = minPresence.getValueI();
		for(UUID uuid : known.keySet())
		{
			if(currentUuids.contains(uuid))
				continue;
				
			// Hasn't been present long enough to count as a real player -
			// most likely a fake/NPC player briefly flickering in the tab
			// list. Drop it silently, no message, no vanish tracking.
			int seenSince = firstSeen.getOrDefault(uuid, tickCounter);
			if(tickCounter - seenSince < minTicks)
				continue;
			
			String name = nameCache.getOrDefault(uuid, uuid.toString());
			
			if(confirmedLeftByMessage.remove(name))
			{
				pendingRemoval.remove(uuid);
				continue;
			}
			
			pendingRemoval.putIfAbsent(uuid, tickCounter);
		}
		
		// Clean up anything that both left the tablist AND isn't being
		// tracked for removal (either confirmed-left, or too-short-lived
		// to count - both cases fall through without entering
		// pendingRemoval above).
		known.keySet().removeIf(uuid -> {
			if(currentUuids.contains(uuid))
				return false;
			if(pendingRemoval.containsKey(uuid))
				return false;
			firstSeen.remove(uuid);
			return true;
		});
		
		int delay = vanishDelay.getValueI();
		pendingRemoval.entrySet().removeIf(e -> {
			UUID uuid = e.getKey();
			if(currentUuids.contains(uuid))
				return true;
			
			if(tickCounter - e.getValue() < delay)
				return false;
			
			String name = nameCache.getOrDefault(uuid, uuid.toString());
			vanished.add(uuid);
			known.remove(uuid);
			firstSeen.remove(uuid);
			ChatUtils.message(name + " has vanished.");
			return true;
		});
	}
	
	private String formatGameMode(GameType gm)
	{
		return gm == null ? "unknown" : gm.getShortDisplayName().getString();
	}
	
	// =================================================================
	// Join/leave message matching
	// =================================================================
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		String plain = event.getComponent().getString();
		
		String leaveRegex = customJoinLeaveMessages.isChecked()
			? patternToRegex(leavePattern.getValue())
			: patternToRegex("{player} left the game");
		
		Matcher m = Pattern.compile(leaveRegex).matcher(plain);
		if(m.matches())
			confirmedLeftByMessage.add(m.group("player"));
	}
	
	private String patternToRegex(String pattern)
	{
		String escaped = Pattern.quote(pattern);
		return escaped.replace("{player}", "\\E(?<player>.+)\\Q");
	}
}
