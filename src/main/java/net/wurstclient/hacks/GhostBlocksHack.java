/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"ghost blocks", "client side blocks", "ClientSideEdit",
	"fake blocks"})
public final class GhostBlocksHack extends Hack
	implements PacketOutputListener, PacketInputListener
{
	private final CheckboxSetting infiniteBlocks =
		new CheckboxSetting("Infinite blocks",
			"Placing a block doesn't use it up, so you can place as many as you"
				+ " want.",
			true);
	
	private final CheckboxSetting freezeWorld = new CheckboxSetting(
		"Freeze world",
		"Also ignores block changes from the server, e.g. from other players,"
			+ " while GhostBlocks is enabled. They are applied once you"
			+ " disable it.",
		true);
	
	// block changes from the server that were held back, newest per position
	private final Map<BlockPos, BlockState> heldBackChanges =
		new ConcurrentHashMap<>();
	
	private ClientLevel level;
	
	public GhostBlocksHack()
	{
		super("GhostBlocks");
		setCategory(Category.BLOCKS);
		addSetting(infiniteBlocks);
		addSetting(freezeWorld);
	}
	
	@Override
	protected void onEnable()
	{
		heldBackChanges.clear();
		level = MC.level;
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		
		// only resync if we're still in the same world
		if(level != null && level == MC.level)
		{
			// apply the server's block changes that were held back
			heldBackChanges.forEach((pos, state) -> level
				.setServerVerifiedBlockState(pos, state, 19));
			
			// Placing and breaking blocks is predicted by the client and only
			// undone once the server confirms it. Confirming everything at
			// once puts back the real block states of all ghost blocks.
			level.handleBlockChangedAck(Integer.MAX_VALUE);
		}
		
		heldBackChanges.clear();
		level = null;
	}
	
	public boolean shouldKeepPlacedItem()
	{
		return isEnabled() && infiniteBlocks.isChecked();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		Packet<?> packet = event.getPacket();
		
		// placing blocks (and any other right-click on a block)
		if(packet instanceof ServerboundUseItemOnPacket)
		{
			event.cancel();
			return;
		}
		
		// breaking blocks
		if(packet instanceof ServerboundPlayerActionPacket p
			&& isBreakingAction(p.getAction()))
			event.cancel();
	}
	
	private boolean isBreakingAction(
		ServerboundPlayerActionPacket.Action action)
	{
		return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
			|| action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
			|| action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!freezeWorld.isChecked())
			return;
		
		Packet<?> packet = event.getPacket();
		
		if(packet instanceof ClientboundBlockUpdatePacket p)
		{
			heldBackChanges.put(p.getPos(), p.getBlockState());
			event.cancel();
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket p)
		{
			p.runUpdates(
				(pos, state) -> heldBackChanges.put(pos.immutable(), state));
			event.cancel();
		}
	}
}
