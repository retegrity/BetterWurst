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
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Hits the player from the front while they sprint toward the attacker, the
 * same way Player.attack() does, and checks that JumpReset makes the
 * knockback push them back less far.
 */
public final class JumpResetHackTest extends SingleplayerTest
{
	public JumpResetHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing JumpReset hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		context.runOnClient(mc -> mc.options.fovEffectScale().set(0.0));
		
		double withoutReset = measureKnockback();
		runWurstCommand("t JumpReset on");
		double withReset = measureKnockback();
		runWurstCommand("t JumpReset off");
		
		logger.info("Knocked back {} blocks without JumpReset, {} with it",
			withoutReset, withReset);
		if(withReset > withoutReset - 0.5)
			throw new AssertionError(
				"JumpReset didn't reduce the knockback: " + withoutReset
					+ " blocks without it, " + withReset + " with it");
		
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
	}
	
	/**
	 * Sprints south, gets hit from the south and returns a number that is
	 * bigger the further the knockback pushed the player back (north).
	 */
	private double measureKnockback()
	{
		runCommand("tp @s 0 -60 -10 0 0");
		context.waitFor(mc -> mc.player.getY() == -60 && mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		input.holdKey(options -> options.keyUp);
		input.holdKey(options -> options.keySprint);
		context.waitFor(mc -> mc.player.isSprinting() && mc.player.onGround());
		context.waitTicks(5);
		
		double startZ = context.computeOnClient(mc -> mc.player.getZ());
		ServerPlayer player =
			server.computeOnServer(s -> connection.getServerPlayer());
		server.runOnServer(s -> {
			// like Player.attack(): damage, knockback away from the attacker
			// in the south, then send the velocity and restore the old one
			Vec3 oldMovement = player.getDeltaMovement();
			player.invulnerableTime = 0;
			DamageSource source = player.damageSources().generic();
			player.hurtServer(player.level(), source, 1);
			player.knockback(0.4, 0, 1, source, 1);
			player.connection
				.send(new ClientboundSetEntityMotionPacket(player));
			player.hurtMarked = false;
			player.setDeltaMovement(oldMovement);
		});
		
		context.waitFor(mc -> mc.player.hurtTime > 0);
		context.waitTicks(10);
		double endZ = context.computeOnClient(mc -> mc.player.getZ());
		
		input.releaseKey(options -> options.keyUp);
		input.releaseKey(options -> options.keySprint);
		server.runOnServer(s -> player.setHealth(player.getMaxHealth()));
		context.waitFor(mc -> mc.player.onGround() && mc.player.hurtTime == 0
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		// holding W moves the player south, the knockback north, so less
		// knockback means ending up further south
		return startZ - endZ;
	}
}
