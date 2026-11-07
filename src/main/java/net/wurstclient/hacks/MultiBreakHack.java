/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMultiPlayerGameMode;

/**
 * Breaks a vertical 3x3 area around every block that you mine, like a drill.
 *
 * <p>
 * The vanilla server only mines one block at a time: starting to mine
 * another block replaces the one being mined, and a "stop destroying" packet
 * only counts for that block, once at least 70% of its mining time has
 * passed (including the current tick). So the surrounding blocks can only
 * break together with the middle one if 70% of their mining time fits into a
 * single tick. Then "start" and "stop" in the same tick break them right
 * away, like SpeedNuker does (e.g. stone with an Efficiency V pickaxe, even
 * without Haste). Those packets are sent right after the middle block's
 * packet, since sending them before it would make the server forget about
 * the middle block.
 *
 * <p>
 * Surrounding blocks that couldn't be broken right away are retried for a
 * second. Mining is 5x slower in the air, so when tunneling with jumps, they
 * often aren't instant at the moment the middle block breaks, but are once
 * you land. The server also uses the on-ground state from your last movement
 * packet, so right after landing it can disagree and put them back.
 */
@SearchTags({"multi break", "drill", "hammer", "3x3", "area mining",
	"AreaMiner", "area miner"})
public final class MultiBreakHack extends Hack implements UpdateListener
{
	// the vanilla server accepts "stop destroying" at 70% progress
	private static final float SERVER_FINISH_PROGRESS = 0.7F;
	
	// how long to keep retrying surrounding blocks
	private static final int RETRY_TICKS = 20;
	
	private final ArrayList<Retry> retries = new ArrayList<>();
	private int playerMining;
	private boolean breakingArea;
	
	public MultiBreakHack()
	{
		super("MultiBreak");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	protected void onEnable()
	{
		retries.clear();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		retries.clear();
	}
	
	/**
	 * Marks the start of your own mining, by vanilla's left click or
	 * AutoMine, as opposed to Nuker-like hacks. Has to be followed by
	 * {@link #endPlayerMining()}.
	 */
	public void beginPlayerMining()
	{
		playerMining++;
	}
	
	public void endPlayerMining()
	{
		playerMining--;
	}
	
	/**
	 * Whether MultiBreak is breaking blocks right now, which shouldn't count
	 * as blocks that you mined.
	 */
	public boolean isBreakingArea()
	{
		return breakingArea;
	}
	
	/**
	 * Called by MultiPlayerGameModeMixin right after you broke a block and
	 * the packet for it was sent.
	 */
	public void onBlockMined(BlockPos center, Direction side)
	{
		if(!isEnabled() || playerMining <= 0 || breakingArea
			|| MC.player == null || MC.level == null
			|| MC.player.isShiftKeyDown())
			return;
		
		// a vertical 3x3 facing the direction you look horizontally
		LocalPlayer player = MC.player;
		Direction facing = player.getDirection();
		Direction right = facing.getClockWise();
		int retryUntil = IMC.getTicks() + RETRY_TICKS;
		
		breakingArea = true;
		try
		{
			for(int dy = -1; dy <= 1; dy++)
				for(int dx = -1; dx <= 1; dx++)
				{
					if(dx == 0 && dy == 0)
						continue;
					
					BlockPos pos = center.relative(right, dx).above(dy);
					BlockState state = MC.level.getBlockState(pos);
					if(!isToolFor(player, state))
						continue;
					
					retries.add(new Retry(pos, side, state.getBlock(), MC.level,
						retryUntil));
					if(canBreakInstantly(player, pos))
						breakInstantly(pos, side);
				}
			
		}finally
		{
			breakingArea = false;
		}
	}
	
	/**
	 * Retries surrounding blocks that weren't instant at first, or that the
	 * server put back.
	 */
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		int ticks = IMC.getTicks();
		retries.removeIf(r -> r.level != MC.level || ticks > r.retryUntil);
		
		// breaking another block would make the server forget about the one
		// that you're mining
		if(retries.isEmpty() || MC.gameMode.isDestroying()
			|| player.isShiftKeyDown())
			return;
		
		breakingArea = true;
		try
		{
			for(Retry retry : retries)
			{
				// still broken, or something else is there now
				if(!MC.level.getBlockState(retry.pos).is(retry.block))
					continue;
				
				if(canBreakInstantly(player, retry.pos))
					breakInstantly(retry.pos, retry.side);
			}
			
		}finally
		{
			breakingArea = false;
		}
	}
	
	/**
	 * Only blocks that your tool is made for, like a drill or hammer (which
	 * also leaves out air and liquids).
	 */
	private boolean isToolFor(LocalPlayer player, BlockState state)
	{
		ItemStack tool = player.getMainHandItem();
		return !state.isAir() && tool.getDestroySpeed(state) > 1;
	}
	
	private boolean canBreakInstantly(LocalPlayer player, BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		if(!isToolFor(player, state))
			return false;
			
		// same reach as for the block you mine (measured to the closest
		// point of the block), the server even allows 1 block more
		if(!player.isWithinBlockInteractionRange(pos, 0))
			return false;
		
		if(player.blockActionRestricted(MC.level, pos,
			MC.gameMode.getPlayerMode()))
			return false;
		
		if(player.getAbilities().instabuild)
			return true;
		
		return state.getDestroyProgress(player, MC.level,
			pos) >= SERVER_FINISH_PROGRESS;
	}
	
	private void breakInstantly(BlockPos pos, Direction side)
	{
		IMultiPlayerGameMode gameMode = IMC.getGameMode();
		BlockState state = MC.level.getBlockState(pos);
		
		// the server breaks blocks at 100% (and anything in creative) on
		// "start", and blocks at 70% or more on "stop" in the same tick
		boolean breaksOnStart = MC.player.getAbilities().instabuild
			|| state.getDestroyProgress(MC.player, MC.level, pos) >= 1;
		if(breaksOnStart)
		{
			gameMode.destroyBlockWithPacket(Action.START_DESTROY_BLOCK, pos,
				side);
			return;
		}
		
		gameMode.sendPlayerActionC2SPacket(Action.START_DESTROY_BLOCK, pos,
			side);
		gameMode.destroyBlockWithPacket(Action.STOP_DESTROY_BLOCK, pos, side);
	}
	
	/**
	 * A surrounding block to retry until the given tick, as long as the same
	 * block is still there.
	 */
	private record Retry(BlockPos pos, Direction side, Block block,
		ClientLevel level, int retryUntil)
	{}
}
