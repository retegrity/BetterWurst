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
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Lunges once, then three times in one burst, and checks that the server
 * applied all three lunges of the burst.
 */
public final class FastLungeHackTest extends SingleplayerTest
{
	public FastLungeHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing FastLunge hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		runCommand("give @s diamond_spear[enchantments={lunge:3}]");
		context.waitFor(
			mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SPEAR));
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(1));
		
		double single = measureLunge(1, 1);
		double burst = measureLunge(3, 4);
		
		logger.info("1 lunge moved {} blocks, a burst of 3 moved {}", single,
			burst);
		if(burst < single * 2.5)
			throw new AssertionError("A burst of 3 lunges only moved " + burst
				+ " blocks, compared to " + single + " for 1 lunge");
		
		// Clean up
		clearInventory();
		context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		runWurstCommand("setslider FastLunge lunges_per_burst 1");
		runWurstCommand("setslider FastLunge max_lunges 0");
		ServerPlayer player = server
			.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst());
		server.runOnServer(s -> {
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(5);
		});
		runCommand("gamemode creative");
		context.waitFor(mc -> mc.player.getAbilities().instabuild);
		runCommand("tp @s 0 -57 0 0 0");
		context.waitFor(mc -> mc.player.getY() == -57);
		context.runOnClient(mc -> {
			mc.player.setDeltaMovement(Vec3.ZERO);
			mc.player.avatarState().resetBob();
		});
		waitForHandSwing();
		clearParticles();
		clearChat();
	}
	
	/**
	 * Lunges south the given number of times in one burst and returns how
	 * far the player moved.
	 */
	private double measureLunge(int lunges, int expectedDamage)
	{
		runCommand("tp @s 5 -60 -15 0 0");
		context.waitFor(mc -> mc.player.getY() == -60 && mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		runWurstCommand("setslider FastLunge lunges_per_burst " + lunges);
		runWurstCommand("setslider FastLunge max_lunges " + lunges);
		
		double startZ = context.computeOnClient(mc -> mc.player.getZ());
		runWurstCommand("t FastLunge on");
		
		// every lunge costs 1 durability, confirmed by the server
		context.waitFor(mc -> mc.player.getInventory().getItem(0)
			.getDamageValue() >= expectedDamage);
		context.waitFor(mc -> mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		int damage = context.computeOnClient(
			mc -> mc.player.getInventory().getItem(0).getDamageValue());
		if(damage != expectedDamage)
			throw new AssertionError("Expected the spear to have "
				+ expectedDamage + " damage, found " + damage);
		
		return context.computeOnClient(mc -> mc.player.getZ()) - startZ;
	}
}
