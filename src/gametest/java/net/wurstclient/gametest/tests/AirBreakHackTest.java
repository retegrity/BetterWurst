/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Mines a stone block with a diamond pickaxe on the ground, then while
 * hanging in the air (in a cobweb, which makes you fall extremely slowly)
 * with and without AirBreak, and counts the ticks until the server has
 * broken it. In the air, vanilla mines 5x slower.
 */
public final class AirBreakHackTest extends SingleplayerTest
{
	
	public AirBreakHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing AirBreak hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		runCommand("give @s diamond_pickaxe");
		
		int ground = mine(-60, false);
		int without = mine(-50, true);
		runWurstCommand("t AirBreak on");
		int with = mine(-50, true);
		runWurstCommand("t AirBreak off");
		logger.info("Mined stone in {} ticks on the ground, {} in the air"
			+ " without AirBreak and {} with it", ground, without, with);
		
		if(without < ground * 3)
			throw new AssertionError("Mining in the air only took " + without
				+ " ticks without AirBreak (" + ground
				+ " on the ground), so the test doesn't work");
		if(with > ground + 2)
			throw new AssertionError("AirBreak still took " + with
				+ " ticks to mine stone in the air (" + ground
				+ " on the ground)");
		
		// Clean up
		runCommand("setblock 80 -49 79 air");
		clearInventory();
		runCommand("gamemode creative");
		context.waitFor(mc -> mc.player.getAbilities().instabuild);
		runCommand("tp @s 0 -57 0 0 0");
		context.waitFor(mc -> mc.player.getY() == -57);
		context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
		clearParticles();
		clearChat();
	}
	
	/**
	 * Stands (or hangs in a cobweb) at the given height in front of a stone
	 * block and holds left click until the server has broken it. Returns how
	 * many ticks that took.
	 */
	private int mine(int y, boolean inAir)
	{
		BlockPos stone = new BlockPos(80, y + 1, 79);
		runCommand("setblock 80 " + (y + 1) + " 79 stone");
		if(inAir)
			runCommand("setblock 80 " + y + " 80 cobweb");
		runCommand("tp @s 80.5 " + y + " 80.5 180 0");
		context.waitFor(mc -> Math.abs(mc.player.getZ() - 80.5) < 1e-6
			&& Math.abs(mc.player.getY() - y) < 0.5
			&& mc.level.getBlockState(stone).is(Blocks.STONE)
			&& mc.hitResult instanceof BlockHitResult hit
			&& hit.getBlockPos().equals(stone)
			&& mc.player.onGround() != inAir);
		
		// vanilla waits 5 ticks of mining after breaking a block, which would
		// carry over from the last run
		context.runOnClient(mc -> mc.gameMode.destroyDelay = 0);
		
		input.holdKey(options -> options.keyAttack);
		int ticks = 0;
		while(!server
			.computeOnServer(s -> s.overworld().getBlockState(stone).isAir()))
		{
			context.waitTick();
			if(++ticks > 100)
				throw new AssertionError("Couldn't mine the stone at all");
		}
		input.releaseKey(options -> options.keyAttack);
		if(inAir)
			runCommand("setblock 80 " + y + " 80 air");
		return ticks;
	}
}
