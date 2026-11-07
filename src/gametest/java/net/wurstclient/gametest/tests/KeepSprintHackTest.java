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
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;
import net.wurstclient.settings.AttackSwingSetting.AttackSwing;

/**
 * Sprints into a husk and a boat and hits them, with and without
 * KeepSprint. The husk can only be hurt on the server, which then turns off
 * your sprint. The boat can also be hurt on the client, which slows you down
 * and turns off your sprint right away.
 */
public final class KeepSprintHackTest extends SingleplayerTest
{
	private static final double X = 30.5;
	private static final double START_Z = 20.5;
	private static final double TARGET_Z = 15.5;
	
	// walking speed is 0.1, sprinting adds 30%
	private static final double MIN_SPRINT_SPEED = 0.125;
	
	public KeepSprintHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing KeepSprint hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		context.runOnClient(mc -> mc.options.fovEffectScale().set(0.0));
		
		// without KeepSprint first, to make sure that the test notices it
		testZombie(false);
		testBoat(false);
		runWurstCommand("t KeepSprint on");
		testZombie(true);
		testBoat(true);
		runWurstCommand("t KeepSprint off");
		
		// Clean up
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
		// from killing the zombie that you hit
		clearToasts();
	}
	
	private void testZombie(boolean keepSprint)
	{
		// a husk, since zombies burn in daylight, and a hit during the
		// invulnerability after burning wouldn't do anything
		runCommand("summon husk " + X + " -60 " + TARGET_Z
			+ " {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
		context.waitFor(mc -> getTarget(mc, Zombie.class) != null);
		sprintAndHit(Zombie.class);
		
		// the server's sprinting flag and movement speed arrive later
		context.waitTicks(3);
		boolean sprinting =
			context.computeOnClient(mc -> mc.player.isSprinting());
		double speed = context.computeOnClient(
			mc -> mc.player.getAttributeValue(Attributes.MOVEMENT_SPEED));
		stopWalking("husk");
		
		String state = "sprinting=" + sprinting + ", speed=" + speed;
		if(keepSprint && (!sprinting || speed < MIN_SPRINT_SPEED))
			throw new AssertionError(
				"KeepSprint lost the sprint after hitting a zombie: " + state);
		if(!keepSprint && sprinting)
			throw new AssertionError(
				"Hitting a zombie didn't stop the sprint without KeepSprint,"
					+ " so the test doesn't work: " + state);
	}
	
	private void testBoat(boolean keepSprint)
	{
		runCommand("summon oak_boat " + X + " -60 " + TARGET_Z);
		context.waitFor(mc -> getTarget(mc, Boat.class) != null);
		
		// checked in the same tick, before the server has a say
		String result = sprintAndHit(Boat.class);
		stopWalking("oak_boat");
		
		boolean slowedDown = result.startsWith("slowed");
		if(keepSprint && slowedDown)
			throw new AssertionError(
				"KeepSprint didn't stop the slowdown from hitting a boat: "
					+ result);
		if(!keepSprint && !slowedDown)
			throw new AssertionError(
				"Hitting a boat didn't slow you down without KeepSprint,"
					+ " so the test doesn't work: " + result);
	}
	
	/**
	 * Sprints towards the target and hits it at full attack strength, once it
	 * is within 3 blocks. Returns whether hitting it slowed you down or
	 * stopped your sprint on the client, right after the hit.
	 */
	private String sprintAndHit(Class<? extends Entity> type)
	{
		runCommand("tp @s " + X + " -60 " + START_Z + " 180 0");
		context.waitFor(mc -> Math.abs(mc.player.getZ() - START_Z) < 1e-6
			&& mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		input.holdKey(options -> options.keyUp);
		context.runOnClient(mc -> mc.player.setSprinting(true));
		waitFor(
			mc -> getTarget(mc, type).distanceTo(mc.player) <= 3
				&& mc.player.getAttackStrengthScale(0.5F) >= 1,
			"Never got within reach of the " + type.getSimpleName());
		
		return context.computeOnClient(mc -> {
			if(!mc.player.isSprinting())
				throw new AssertionError("Wasn't sprinting before the hit");
			
			Vec3 before = mc.player.getDeltaMovement();
			mc.gameMode.attack(mc.player, getTarget(mc, type));
			// like a real left click, without the swing the server didn't
			// stop the sprint in this test
			AttackSwing.CLIENT.swing();
			Vec3 after = mc.player.getDeltaMovement();
			
			String state = "before=" + before + ", after=" + after
				+ ", sprinting=" + mc.player.isSprinting();
			boolean slowed =
				after.horizontalDistance() < before.horizontalDistance() * 0.9
					|| !mc.player.isSprinting();
			return (slowed ? "slowed: " : "kept: ") + state;
		});
	}
	
	private void stopWalking(String type)
	{
		input.releaseKey(options -> options.keyUp);
		runCommand("kill @e[type=" + type + "]");
		runCommand("kill @e[type=item]");
		context.waitFor(mc -> mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
	}
	
	private static <T extends Entity> T getTarget(Minecraft mc, Class<T> type)
	{
		for(Entity entity : mc.level.entitiesForRendering())
			if(type.isInstance(entity))
				return type.cast(entity);
			
		return null;
	}
}
