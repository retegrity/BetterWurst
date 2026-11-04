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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Sprints off a platform in the air with Scaffold in different directions
 * and checks that it builds a bridge fast enough that you don't fall. Bridges
 * that are slightly off straight or off diagonal used to miss the clicked
 * face and let you fall.
 */
public final class ScaffoldHackTest extends SingleplayerTest
{
	private static final int Y = -45;
	
	public ScaffoldHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing Scaffold hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		context.runOnClient(mc -> mc.options.fovEffectScale().set(0.0));
		
		// straight with a little drift, diagonal, and in between
		bridge(-40, -40, 8, false);
		bridge(-60, -40, 45, false);
		bridge(-80, -40, 65, false);
		
		// like jump-bridging with NCPHop on Rewound
		runWurstCommand("setmode SpeedHack mode ncphop");
		runWurstCommand("t SpeedHack on");
		bridge(-100, -40, 65, true);
		runWurstCommand("t SpeedHack off");
		runWurstCommand("setmode SpeedHack mode vanilla");
		
		// Clean up
		ServerPlayer player =
			server.computeOnServer(s -> connection.getServerPlayer());
		server.runOnServer(s -> {
			player.setHealth(player.getMaxHealth());
			player.getFoodData().setFoodLevel(20);
		});
		runCommand("fill -130 " + (Y - 5) + " -45 -20 " + (Y + 3) + " 0 air");
		clearInventory();
		runCommand("gamemode creative");
		context.waitFor(mc -> mc.player.getAbilities().instabuild);
		runCommand("tp @s 0 -57 0 0 0");
		context.waitFor(mc -> mc.player.getY() == -57);
		context.runOnClient(mc -> {
			mc.player.setDeltaMovement(Vec3.ZERO);
			mc.player.avatarState().resetBob();
			mc.options.fovEffectScale().set(1.0);
		});
		clearParticles();
		clearChat();
	}
	
	private void bridge(int x, int z, float yaw, boolean hopping)
	{
		// a small platform to start the bridge from
		runCommand("fill " + (x - 1) + " " + Y + " " + (z - 1) + " " + (x + 1)
			+ " " + Y + " " + (z + 1) + " stone");
		runCommand("give @s stone 64");
		runCommand("tp @s " + (x + 0.5) + " " + (Y + 1) + " " + (z + 0.5) + " "
			+ yaw + " 30");
		context.waitFor(mc -> mc.player.getY() == Y + 1 && mc.player.onGround()
			&& Math.abs(mc.player.getX() - (x + 0.5)) < 1e-3
			&& Math.abs(mc.player.getZ() - (z + 0.5)) < 1e-3
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		runWurstCommand("t Scaffold on");
		input.holdKey(options -> options.keyUp);
		input.holdKey(options -> options.keySprint);
		
		Vec3 start = context.computeOnClient(mc -> mc.player.position());
		double lowestY = start.y;
		for(int i = 0; i < 50; i++)
		{
			context.waitTick();
			lowestY = Math.min(lowestY,
				context.computeOnClient(mc -> mc.player.getY()));
		}
		Vec3 end = context.computeOnClient(mc -> mc.player.position());
		
		// keep building until you have stopped, or you would slide off the
		// end of the bridge and take fall damage
		input.releaseKey(options -> options.keyUp);
		input.releaseKey(options -> options.keySprint);
		context.waitFor(mc -> mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		runWurstCommand("t Scaffold off");
		
		double distance = end.subtract(start).horizontalDistance();
		logger.info("Scaffold at {}°{}: {} blocks, lowest y {}", yaw,
			hopping ? " with NCPHop" : "", distance, lowestY);
		
		if(lowestY < Y + 0.5)
			throw new AssertionError("Fell while bridging at " + yaw + "°"
				+ (hopping ? " with NCPHop" : "") + " after " + distance
				+ " blocks");
		
		if(distance < 8)
			throw new AssertionError(
				"Only bridged " + distance + " blocks at " + yaw + "°");
		
		clearInventory();
	}
}
