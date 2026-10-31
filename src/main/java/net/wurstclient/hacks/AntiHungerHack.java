/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.PacketUtils;

@DontSaveState
@SearchTags({"anti hunger", "NoSprint", "no sprint", "NoGround", "no ground"})
public final class AntiHungerHack extends Hack
	implements PacketOutputListener, UpdateListener
{
	private final CheckboxSetting noGround = new CheckboxSetting("NoGround",
		"Tells the server that you are in the air while you are walking on the"
			+ " ground, so walking, sprinting and jumping don't use up hunger.\n\n"
			+ "Doesn't do anything while you are in water or riding something.",
		true);
	
	private final CheckboxSetting noSprint = new CheckboxSetting("NoSprint",
		"Hides your sprinting from the server, so sprinting doesn't use up"
			+ " more hunger than walking.\n\n"
			+ "Also lets Criticals work while you are sprinting.\n\n"
			+ "Doesn't do anything while you are in water, since swimming"
			+ " needs the server to see you sprint.",
		true);
	
	// whether the server currently thinks that the player is sprinting
	private boolean serverSprinting;
	private LocalPlayer lastPlayer;
	
	public AntiHungerHack()
	{
		super("AntiHunger");
		setCategory(Category.MOVEMENT);
		addSetting(noGround);
		addSetting(noSprint);
	}
	
	@Override
	protected void onEnable()
	{
		if(noGround.isChecked())
			WURST.getHax().noFallHack.setEnabled(false);
		
		lastPlayer = MC.player;
		serverSprinting = MC.player != null && MC.player.isSprinting();
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		
		// tell the server about the real sprinting state again
		LocalPlayer player = MC.player;
		if(player != null && player.isSprinting() != serverSprinting)
			sendSprint(player, player.isSprinting());
	}
	
	/**
	 * Returns true if the server currently can't see that the player is
	 * sprinting because of this hack's NoSprint option.
	 */
	public boolean isHidingSprint()
	{
		return isEnabled() && MC.player != null && shouldHideSprint(MC.player);
	}
	
	private boolean shouldHideSprint(LocalPlayer player)
	{
		return noSprint.isChecked() && !player.isSwimming()
			&& !player.isInWater() && !player.isUnderWater();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		// new player after joining / respawning -> server sprint state reset
		if(player != lastPlayer)
		{
			lastPlayer = player;
			serverSprinting = false;
		}
		
		boolean wanted = player.isSprinting() && !shouldHideSprint(player);
		if(wanted != serverSprinting)
			sendSprint(player, wanted);
	}
	
	private void sendSprint(LocalPlayer player, boolean sprinting)
	{
		// serverSprinting gets updated in handleSprintPacket()
		player.connection.send(new ServerboundPlayerCommandPacket(player,
			sprinting ? Action.START_SPRINTING : Action.STOP_SPRINTING));
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		if(event.getPacket() instanceof ServerboundPlayerCommandPacket packet)
		{
			handleSprintPacket(event, packet, player);
			return;
		}
		
		if(!(event.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
		
		if(!noGround.isChecked())
			return;
		
		if(!player.onGround() || player.fallDistance > 0.5)
			return;
		
		if(player.isPassenger() || player.isInWater() || player.isSwimming()
			|| player.isUnderWater())
			return;
		
		if(MC.gameMode.isDestroying())
			return;
		
		event.setPacket(PacketUtils.modifyOnGround(packet, false));
	}
	
	private void handleSprintPacket(PacketOutputEvent event,
		ServerboundPlayerCommandPacket packet, LocalPlayer player)
	{
		Action action = packet.getAction();
		if(action == Action.START_SPRINTING)
		{
			if(shouldHideSprint(player) || serverSprinting)
				event.cancel();
			else
				serverSprinting = true;
			
		}else if(action == Action.STOP_SPRINTING)
		{
			if(!serverSprinting)
				event.cancel();
			else
				serverSprinting = false;
		}
	}
}
