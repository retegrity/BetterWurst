/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ServerData;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.discordrpc.DiscordIpcClient;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"discord rpc", "discord rich presence", "rich presence", "rpc",
	"discord presence", "discord"})
public final class DiscordRpcHack extends Hack
{
	private static final String CLIENT_ID = "1529845005752074300";
	private static final String LARGE_IMAGE = "wurst";
	
	private static final long MIN_INTERVAL_MS = 4000;
	private static final long HEARTBEAT_MS = 15000;
	private static final long RECONNECT_MS = 15000;
	
	private final CheckboxSetting showServer =
		new CheckboxSetting("Show server name",
			"Shows the name you gave the server in your server list. The IP"
				+ " address is never shown.\n\n"
				+ "If disabled, it only says that you are playing multiplayer.",
			true);
	
	private final CheckboxSetting showHackCount = new CheckboxSetting(
		"Show hack count", "Shows how many hacks you have enabled.", false);
	
	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> pollTask;
	private DiscordIpcClient client;
	
	// only accessed from the scheduler thread
	private String lastKey;
	private String lastText;
	private long stateSince;
	private long lastSent;
	private long lastConnectAttempt;
	
	public DiscordRpcHack()
	{
		super("DiscordRPC");
		setCategory(Category.OTHER);
		addSetting(showServer);
		addSetting(showHackCount);
	}
	
	@Override
	protected void onEnable()
	{
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "DiscordRPC");
			thread.setDaemon(true);
			return thread;
		});
		
		lastKey = null;
		lastText = null;
		lastSent = 0;
		lastConnectAttempt = 0;
		
		// Minecraft's state may only be read on the main thread, so this just
		// asks the main thread to take a snapshot.
		pollTask = scheduler.scheduleWithFixedDelay(
			() -> MC.execute(this::takeSnapshot), 0, 1, TimeUnit.SECONDS);
	}
	
	@Override
	protected void onDisable()
	{
		if(pollTask != null)
			pollTask.cancel(false);
		
		ScheduledExecutorService oldScheduler = scheduler;
		scheduler = null;
		pollTask = null;
		
		if(oldScheduler == null)
			return;
		
		oldScheduler.execute(() -> {
			if(client != null)
				try
				{
					if(client.isConnected())
						client.setActivity(null);
					
				}catch(IOException e)
				{
					// ignore
				}finally
				{
					client.close();
					client = null;
				}
		});
		oldScheduler.shutdown();
	}
	
	/** Runs on the main thread. */
	private void takeSnapshot()
	{
		ScheduledExecutorService currentScheduler = scheduler;
		if(!isEnabled() || currentScheduler == null)
			return;
		
		String place;
		if(MC.level == null)
			place = "In the main menu";
		else
		{
			ServerData server = MC.getCurrentServer();
			if(server != null && !server.isRealm())
				place =
					showServer.isChecked() ? "Playing on " + server.name.strip()
						: "Playing multiplayer";
			else if(server != null)
				place = "Playing on a Realm";
			else
				place = "Playing singleplayer";
		}
		
		String text = place;
		if(showHackCount.isChecked())
		{
			long count = WurstClient.INSTANCE.getHax().getAllHax().stream()
				.filter(Hack::isEnabled).count();
			text +=
				" · " + count + (count == 1 ? " hack" : " hacks") + " enabled";
		}
		
		String details = "Wurst Client " + WurstClient.VERSION + " · "
			+ "Minecraft " + SharedConstants.getCurrentVersion().name();
		String finalText = text;
		String finalPlace = place;
		
		try
		{
			currentScheduler
				.execute(() -> update(details, finalPlace, finalText));
			
		}catch(RuntimeException e)
		{
			// scheduler was shut down in the meantime
		}
	}
	
	/** Runs on the scheduler thread. */
	private void update(String details, String place, String text)
	{
		long now = System.currentTimeMillis();
		
		// the timer restarts whenever the player goes somewhere else
		boolean placeChanged = !Objects.equals(place, lastKey);
		if(placeChanged)
			stateSince = now;
		
		boolean textChanged = !Objects.equals(text, lastText);
		boolean due =
			placeChanged || textChanged || now - lastSent >= HEARTBEAT_MS;
		if(!due || now - lastSent < MIN_INTERVAL_MS)
			return;
		
		if(client == null || !client.isConnected())
		{
			if(now - lastConnectAttempt < RECONNECT_MS)
				return;
			
			lastConnectAttempt = now;
			client = new DiscordIpcClient(CLIENT_ID);
			try
			{
				client.connect();
				// make sure the first activity gets sent right away
				lastKey = null;
				lastText = null;
				stateSince = now;
				
			}catch(IOException e)
			{
				// Discord probably isn't running, try again later
				client.close();
				client = null;
				return;
			}
		}
		
		JsonObject timestamps = new JsonObject();
		timestamps.addProperty("start", stateSince / 1000);
		
		JsonObject assets = new JsonObject();
		assets.addProperty("large_image", LARGE_IMAGE);
		assets.addProperty("large_text", "Wurst Client");
		
		JsonObject activity = new JsonObject();
		activity.addProperty("details", details);
		activity.addProperty("state", text);
		activity.add("timestamps", timestamps);
		activity.add("assets", assets);
		
		try
		{
			client.setActivity(activity);
			lastKey = place;
			lastText = text;
			lastSent = now;
			
		}catch(IOException e)
		{
			client.close();
			client = null;
		}
	}
}
