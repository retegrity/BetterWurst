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
 * Sprints with SpeedHack's NCPHop mode and checks every move against the
 * bunny hop rules of NoCheatPlus' SurvivalFly check.
 */
public final class SpeedHackHackTest extends SingleplayerTest
{
	private static final double BASE = 0.221 * 1.30000002;
	
	public SpeedHackHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing SpeedHack hack");
		runCommand("gamemode survival");
		context.waitFor(mc -> !mc.player.getAbilities().instabuild);
		context.runOnClient(mc -> mc.options.fovEffectScale().set(0.0));
		
		runCommand("tp @s 50 -60 40 0 0");
		context.waitFor(mc -> mc.player.getY() == -60 && mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		
		runWurstCommand("setmode SpeedHack mode ncphop");
		runWurstCommand("t SpeedHack on");
		input.holdKey(options -> options.keyUp);
		input.holdKey(options -> options.keySprint);
		
		ArrayList<Move> moves = new ArrayList<>();
		Vec3 last = context.computeOnClient(mc -> mc.player.position());
		for(int i = 0; i < 40; i++)
		{
			context.waitTick();
			Vec3 pos = context.computeOnClient(mc -> mc.player.position());
			boolean sprinting =
				context.computeOnClient(mc -> mc.player.isSprinting());
			moves.add(new Move(pos.subtract(last).horizontalDistance(),
				pos.y - last.y, sprinting));
			last = pos;
		}
		
		input.releaseKey(options -> options.keyUp);
		input.releaseKey(options -> options.keySprint);
		runWurstCommand("t SpeedHack off");
		runWurstCommand("setmode SpeedHack mode vanilla");
		
		checkMoves(moves);
		
		// Clean up
		context.waitFor(mc -> mc.player.onGround()
			&& mc.player.getDeltaMovement().horizontalDistanceSqr() < 1e-6);
		ServerPlayer player = server
			.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst());
		server.runOnServer(s -> player.getFoodData().setFoodLevel(20));
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
	
	private void checkMoves(ArrayList<Move> moves)
	{
		int hops = 0;
		double total = 0;
		Move last = null;
		boolean afterHop = false;
		
		for(int i = 0; i < moves.size(); i++)
		{
			Move move = moves.get(i);
			total += move.h;
			logger.info("Move {}: h={} y={}", i, move.h, move.y);
			
			// a jump off the ground
			if(move.y > 0.4)
			{
				hops++;
				if(move.h >= 2.15 * BASE)
					fail(i, move, "hop is faster than 2.15x");
				afterHop = true;
				
			}else if(afterHop && last != null && move.h > BASE)
			{
				afterHop = false;
				if(last.h - move.h < 0.66 * (last.h - BASE))
					fail(i, move, "didn't slow down enough after the hop");
				
			}else if(last != null && move.h > BASE
				&& last.h - move.h < last.h / 160)
				fail(i, move, "didn't slow down by 1/160 in the air");
			
			last = move;
		}
		
		double average = total / moves.size();
		logger.info("NCPHop: {} hops, {} blocks/tick on average", hops,
			average);
		
		if(hops < 2)
			throw new AssertionError("NCPHop only hopped " + hops + " times");
		
		// vanilla sprinting is about 0.28 blocks/tick
		if(average < 0.3)
			throw new AssertionError(
				"NCPHop isn't faster than sprinting: " + average);
	}
	
	private void fail(int i, Move move, String reason)
	{
		throw new AssertionError("NCPHop move " + i + " would flag"
			+ " NoCheatPlus (" + reason + "): h=" + move.h + " y=" + move.y);
	}
	
	private record Move(double h, double y, boolean sprinting)
	{}
}
