/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.ArrayList;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Mines the middle of a 3x3 stone wall with an Efficiency V diamond pickaxe
 * (no Haste) and checks on the server that MultiBreak broke the stone around
 * it in the same tick, but not dirt (wrong tool) or obsidian (not instant).
 * Then checks that sneaking only breaks the middle block.
 */
public final class MultiBreakHackTest extends SingleplayerTest
{
	private static final BlockPos CENTER = new BlockPos(60, -59, 58);
	private static final BlockPos DIRT = CENTER.offset(-1, 1, 0);
	private static final BlockPos OBSIDIAN = CENTER.offset(1, -1, 0);
	
	public MultiBreakHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing MultiBreak hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		runCommand("give @s diamond_pickaxe[enchantments={efficiency:5}]");
		runWurstCommand("t MultiBreak on");
		
		// close to the wall, and almost at the edge of your reach (4.5),
		// where the outer blocks are 4.3-4.4 blocks away from your eyes
		mineWall(60.5, 4.6);
		mineWall(63.3, 1.6);
		
		// surrounding blocks that aren't instant at first, or that the
		// server puts back, are retried
		testRetryWhenNotInstant();
		testRetryWhenPutBack();
		
		// sneaking: only the middle block
		teleport(60.5, 4.6);
		buildWall();
		input.holdKey(options -> options.keyShift);
		context.waitFor(mc -> mc.player.isShiftKeyDown());
		mine(true);
		input.releaseKey(options -> options.keyShift);
		context.waitTicks(5);
		for(BlockPos pos : getWall())
		{
			Block expected =
				pos.equals(CENTER) ? Blocks.AIR : pos.equals(DIRT) ? Blocks.DIRT
					: pos.equals(OBSIDIAN) ? Blocks.OBSIDIAN : Blocks.STONE;
			assertBlock(pos, expected, "after mining while sneaking", true);
		}
		
		// Clean up
		runWurstCommand("t MultiBreak off");
		runCommand("fill 59 -60 58 61 -58 58 air");
		// drops that are still on the ground would be picked up later
		runCommand("kill @e[type=item]");
		clearInventory();
		runCommand("gamemode creative");
		context.waitFor(mc -> mc.player.getAbilities().instabuild);
		runCommand("tp @s 0 -57 0 0 0");
		context.waitFor(mc -> mc.player.getY() == -57);
		context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
		clearParticles();
		clearChat();
		// from picking up the cobblestone
		clearToasts();
	}
	
	/**
	 * Mines the middle of the wall from the given distance and checks that
	 * the whole 3x3 except dirt and obsidian broke, on the server in the same
	 * tick that the middle block broke, then on the client once the server's
	 * block updates arrived.
	 */
	private void mineWall(double z, double pitch)
	{
		teleport(z, pitch);
		buildWall();
		mine(false);
		for(int i = 0; i < 2; i++)
		{
			for(BlockPos pos : getWall())
			{
				Block expected = pos.equals(DIRT) ? Blocks.DIRT
					: pos.equals(OBSIDIAN) ? Blocks.OBSIDIAN : Blocks.AIR;
				assertBlock(pos, expected, "after mining from z=" + z, i == 1);
			}
			context.waitTicks(5);
		}
	}
	
	/**
	 * Mines with Mining Fatigue, which makes the surrounding stone too slow
	 * to break with the middle block, then clears it, after which MultiBreak
	 * should break them.
	 */
	private void testRetryWhenNotInstant()
	{
		teleport(60.5, 4.6);
		buildWall();
		runCommand("effect give @s mining_fatigue 30 0");
		context.waitFor(mc -> mc.player.hasEffect(MobEffects.MINING_FATIGUE));
		mine(false);
		BlockPos stone = CENTER.offset(1, 0, 0);
		assertBlock(stone, Blocks.STONE, "with Mining Fatigue", false);
		
		runCommand("effect clear @s mining_fatigue");
		waitForAirOnServer(stone, "after clearing Mining Fatigue");
		for(BlockPos pos : getWall())
		{
			Block expected = pos.equals(DIRT) ? Blocks.DIRT
				: pos.equals(OBSIDIAN) ? Blocks.OBSIDIAN : Blocks.AIR;
			assertBlock(pos, expected, "after clearing Mining Fatigue", false);
		}
	}
	
	/**
	 * Puts a surrounding block back right after MultiBreak broke it, like
	 * the server does when it disagrees, after which MultiBreak should break
	 * it again.
	 */
	private void testRetryWhenPutBack()
	{
		teleport(60.5, 4.6);
		buildWall();
		mine(false);
		BlockPos stone = CENTER.offset(-1, 0, 0);
		runCommand("setblock " + stone.getX() + " " + stone.getY() + " "
			+ stone.getZ() + " stone");
		context.waitFor(mc -> mc.level.getBlockState(stone).is(Blocks.STONE));
		waitForAirOnServer(stone, "after the server put it back");
	}
	
	private void waitForAirOnServer(BlockPos pos, String when)
	{
		for(int i = 0; i < 20; i++)
		{
			if(server
				.computeOnServer(s -> s.overworld().getBlockState(pos).isAir()))
				return;
			context.waitTick();
		}
		throw new AssertionError("MultiBreak didn't retry " + pos + " " + when);
	}
	
	/**
	 * Stands south of the wall, facing north at its middle.
	 */
	private void teleport(double z, double pitch)
	{
		runCommand("tp @s 60.5 -60 " + z + " 180 " + pitch);
		context.waitFor(mc -> Math.abs(mc.player.getZ() - z) < 1e-6
			&& mc.player.onGround());
	}
	
	private void buildWall()
	{
		// let MultiBreak stop retrying the last wall's blocks first
		context.waitTicks(25);
		runCommand("fill 59 -60 58 61 -58 58 stone");
		runCommand("setblock " + DIRT.getX() + " " + DIRT.getY() + " "
			+ DIRT.getZ() + " dirt");
		runCommand("setblock " + OBSIDIAN.getX() + " " + OBSIDIAN.getY() + " "
			+ OBSIDIAN.getZ() + " obsidian");
		context
			.waitFor(mc -> mc.level.getBlockState(OBSIDIAN).is(Blocks.OBSIDIAN)
				&& mc.level.getBlockState(CENTER).is(Blocks.STONE));
		context.waitFor(mc -> mc.hitResult instanceof BlockHitResult hit
			&& hit.getBlockPos().equals(CENTER));
	}
	
	/**
	 * Holds left click until the middle block is gone on the server.
	 */
	private void mine(boolean sneaking)
	{
		input.holdKey(options -> options.keyAttack);
		int ticks = 0;
		while(!server
			.computeOnServer(s -> s.overworld().getBlockState(CENTER).isAir()))
		{
			context.waitTick();
			if(++ticks > 20)
				throw new AssertionError("Couldn't mine the middle block"
					+ (sneaking ? " while sneaking" : ""));
		}
		input.releaseKey(options -> options.keyAttack);
	}
	
	private ArrayList<BlockPos> getWall()
	{
		ArrayList<BlockPos> wall = new ArrayList<>();
		for(int y = -1; y <= 1; y++)
			for(int x = -1; x <= 1; x++)
				wall.add(CENTER.offset(x, y, 0));
		return wall;
	}
	
	private void assertBlock(BlockPos pos, Block expected, String when,
		boolean checkClient)
	{
		Block onServer = server
			.computeOnServer(s -> s.overworld().getBlockState(pos)).getBlock();
		Block onClient = context
			.computeOnClient(mc -> mc.level.getBlockState(pos)).getBlock();
		if(onServer != expected || checkClient && onClient != expected)
			throw new AssertionError("Expected " + expected + " at " + pos + " "
				+ when + ", but the server has " + onServer
				+ " and the client has " + onClient);
	}
}
