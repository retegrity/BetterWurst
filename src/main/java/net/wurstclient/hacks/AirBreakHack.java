/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PostMotionListener;
import net.wurstclient.hack.Hack;

/**
 * Mines blocks as fast in the air as on the ground.
 *
 * <p>
 * Vanilla mines 5x slower while you aren't on the ground. On the client,
 * PlayerMixin treats you as on the ground for your mining speed while this
 * is active. The server uses the on-ground state from your last movement
 * packet, but only checks it when a "start destroying" packet arrives
 * (whether the block breaks instantly) and when a "stop destroying" packet
 * arrives (whether enough time has passed). So right before those packets,
 * this sends a movement packet that only says you're on the ground. The
 * movement packet at the end of the tick says where you really are again,
 * or, if there isn't one (because you didn't move), this sends your real
 * state itself. That covers mining by hand and with AutoMine, MultiBreak,
 * Nuker, etc.
 *
 * <p>
 * A movement packet that says you're on the ground counts as landing, so
 * the server would deal fall damage for how far you've fallen. So it
 * doesn't do anything once you've fallen far enough for that. It also
 * doesn't do anything while gliding (it would stop the elytra), riding,
 * or in creative or spectator mode.
 */
@SearchTags({"air break", "AirMiner", "air miner", "AirMine", "air mine"})
public final class AirBreakHack extends Hack
	implements PacketOutputListener, PostMotionListener
{
	// stays below the fall distance that the server would turn into damage
	private static final double FALL_DAMAGE_MARGIN = 0.5;
	
	private boolean claimed;
	private boolean sending;
	
	public AirBreakHack()
	{
		super("AirBreak");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	protected void onEnable()
	{
		claimed = false;
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PostMotionListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PostMotionListener.class, this);
		
		if(claimed)
			sendRealState();
	}
	
	/**
	 * Whether mining should be as fast as on the ground right now.
	 */
	public boolean isActive()
	{
		LocalPlayer player = MC.player;
		if(!isEnabled() || player == null || MC.getConnection() == null)
			return false;
		
		if(player.onGround() || player.isFallFlying() || player.isPassenger()
			|| player.getAbilities().instabuild || player.isSpectator())
			return false;
		
		double safeFallDistance =
			player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
		return player.fallDistance < safeFallDistance - FALL_DAMAGE_MARGIN;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(sending)
			return;
		
		Packet<?> packet = event.getPacket();
		
		// the server has your real state again
		if(packet instanceof ServerboundMovePlayerPacket)
		{
			claimed = false;
			return;
		}
		
		if(!(packet instanceof ServerboundPlayerActionPacket actionPacket))
			return;
		
		Action action = actionPacket.getAction();
		if(action != Action.START_DESTROY_BLOCK
			&& action != Action.STOP_DESTROY_BLOCK)
			return;
		
		// once per tick is enough for any number of blocks
		if(claimed || !isActive())
			return;
		
		send(new ServerboundMovePlayerPacket.StatusOnly(true,
			MC.player.horizontalCollision));
		claimed = true;
	}
	
	@Override
	public void onPostMotion()
	{
		// no movement packet this tick, so the server still thinks you're
		// on the ground
		if(claimed)
			sendRealState();
	}
	
	private void sendRealState()
	{
		claimed = false;
		LocalPlayer player = MC.player;
		if(player == null || MC.getConnection() == null)
			return;
		
		send(new ServerboundMovePlayerPacket.StatusOnly(player.onGround(),
			player.horizontalCollision));
	}
	
	private void send(Packet<?> packet)
	{
		sending = true;
		try
		{
			MC.getConnection().send(packet);
			
		}finally
		{
			sending = false;
		}
	}
}
