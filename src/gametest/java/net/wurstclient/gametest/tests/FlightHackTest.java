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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Jumps off a 1 block high step with Flight's Rewound mode and checks every
 * move against the in-air rules of NoCheatPlus' SurvivalFly check (Apr 2017
 * version). Its vertical accounting check is left out, since Rewound has it
 * turned off.
 */
public final class FlightHackTest extends SingleplayerTest
{
	private static final double SPRINT_SPEED = 0.221 * 1.30000002;
	private static final double GRAVITY_MIN = 0.0624;
	private static final double GRAVITY_SPAN = 0.0834 - GRAVITY_MIN;
	private static final double MAX_JUMP_HEIGHT = 1.35;
	private static final int MAX_JUMP_PHASE = 6;
	
	// faster than the default, so the test doesn't take a minute
	private static final double GLIDE_SPEED = 0.02;
	
	public FlightHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing Flight hack (Rewound mode)");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		context.runOnClient(mc -> mc.options.fovEffectScale().set(0.0));
		
		// a 1 block high step to jump off
		runCommand("fill 39 -60 -42 41 -60 -40 stone");
		runCommand("tp @s 40.5 -59 -40.5 0 0");
		context.waitFor(mc -> mc.player.getY() == -59 && mc.player.onGround()
			&& Math.abs(mc.player.getX() - 40.5) < 1e-3
			&& Math.abs(mc.player.getZ() + 40.5) < 1e-3
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		runWurstCommand("setmode Flight mode rewound");
		runWurstCommand(
			"setslider Flight glide_speed_(rewound) " + GLIDE_SPEED);
		runWurstCommand("t Flight on");
		
		input.holdKey(options -> options.keyUp);
		input.holdKey(options -> options.keySprint);
		context.waitFor(mc -> mc.player.isSprinting() && mc.player.onGround());
		context.runOnClient(mc -> mc.player.jumpFromGround());
		
		// record until landing on the ground below the step
		ArrayList<Move> moves = new ArrayList<>();
		for(int i = 0; i < 200; i++)
		{
			Move move = recordMove();
			moves.add(move);
			if(move.toGround())
				break;
		}
		
		input.releaseKey(options -> options.keyUp);
		input.releaseKey(options -> options.keySprint);
		context.waitFor(mc -> mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		runWurstCommand("t Flight off");
		runWurstCommand("setmode Flight mode vanilla");
		runWurstCommand("setslider Flight glide_speed_(rewound) 0.001");
		
		checkMoves(moves);
		
		// Clean up
		runCommand("fill 39 -60 -42 41 -60 -40 air");
		ServerPlayer player = server
			.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst());
		server.runOnServer(s -> {
			player.setHealth(player.getMaxHealth());
			player.getFoodData().setFoodLevel(20);
		});
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
	
	private Move recordMove()
	{
		Vec3 from = context.computeOnClient(mc -> mc.player.position());
		boolean fromGround =
			context.computeOnClient(mc -> mc.player.onGround());
		context.waitTick();
		Vec3 to = context.computeOnClient(mc -> mc.player.position());
		boolean toGround = context.computeOnClient(mc -> mc.player.onGround());
		boolean sprinting =
			context.computeOnClient(mc -> mc.player.isSprinting());
		return new Move(from, to, fromGround, toGround, sprinting);
	}
	
	/**
	 * Checks the moves like NoCheatPlus would.
	 */
	private void checkMoves(ArrayList<Move> moves)
	{
		double setBackY = moves.get(0).from().y;
		double lastY = 0;
		int jumpPhase = 0;
		int airTicks = 0;
		double distance = 0;
		double lowestY = Double.MAX_VALUE;
		
		for(int i = 0; i < moves.size(); i++)
		{
			Move move = moves.get(i);
			double dy = move.to().y - move.from().y;
			double dh = move.to().subtract(move.from()).horizontalDistance();
			double relY = move.from().y - setBackY;
			String info = "move " + i + ": dy=" + dy + " dh=" + dh + " lastY="
				+ lastY + " y=" + relY;
			logger.info(info);
			
			boolean inAir = !move.fromGround() && !move.toGround();
			if(inAir)
			{
				airTicks++;
				distance += dh;
				lowestY = Math.min(lowestY, move.to().y);
				jumpPhase++;
				
				// horizontal: sprinting speed (the lift-off is a normal jump)
				double base = move.sprinting() ? SPRINT_SPEED : 0.221;
				if(dh > base + 1e-6)
					fail(info, "too fast horizontally, limit " + base);
					
				// vertical: the gravity envelope, or anything below 0.006
				// within 1 block of the set back height, or rising less
				// than expected when slower than 0.2
				double gravityY = lastY * 0.98 - GRAVITY_MIN;
				boolean nearSetBack = Math.abs(relY) < 1.0;
				boolean gravityOk =
					dy <= gravityY && dy > gravityY - GRAVITY_SPAN
						|| nearSetBack && dy < 0.006
						|| dy >= 0 && gravityY <= 0.2 && dy <= gravityY;
				if(!gravityOk)
					fail(info, "broke gravity, limit " + gravityY);
				
				// moving upwards again without touching the ground
				if(lastY <= 0 && dy > 0 && lastY != dy)
					fail(info, "rose again in the air");
				
				// staying up for too long
				if(dy >= 0 && jumpPhase > MAX_JUMP_PHASE)
					fail(info, "didn't sink after " + jumpPhase + " moves");
				
				// too high above the set back
				if(dy > 0 && move.to().y - setBackY > MAX_JUMP_HEIGHT)
					fail(info, "too high above the set back");
			}
			
			if(move.toGround())
				setBackY = move.to().y;
			else if(move.fromGround())
			{
				setBackY = move.from().y;
				jumpPhase = 1;
			}
			
			lastY = dy;
		}
		
		logger.info(
			"Rewound flight: {} ticks in the air, {} blocks, lowest" + " y {}",
			airTicks, distance, lowestY);
		
		// up to 1 block above and below the step at 0.02 blocks/tick,
		// a normal jump would be about 12 ticks
		if(airTicks < 90)
			throw new AssertionError(
				"Rewound flight only glided for " + airTicks + " ticks");
		
		if(!moves.get(moves.size() - 1).toGround())
			throw new AssertionError("Rewound flight never landed");
	}
	
	private void fail(String info, String reason)
	{
		throw new AssertionError(
			"Rewound flight would flag NoCheatPlus (" + reason + "): " + info);
	}
	
	private record Move(Vec3 from, Vec3 to, boolean fromGround,
		boolean toGround, boolean sprinting)
	{}
}
