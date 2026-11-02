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
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.wurstclient.WurstClient;
import net.wurstclient.gametest.SingleplayerTest;

/**
 * Starts backtracking a zombie, teleports it away on the server and checks
 * that the client only sees the teleport after the delay.
 */
public final class BacktrackHackTest extends SingleplayerTest
{
	public BacktrackHackTest(ClientGameTestContext context,
		TestSingleplayerContext spContext)
	{
		super(context, spContext);
	}
	
	@Override
	protected void runImpl()
	{
		logger.info("Testing Backtrack hack");
		runCommand("tp @s 5 -60 -15 0 0");
		runCommand("summon zombie 5 -60 -13 {NoAI:1b,Silent:1b}");
		context.waitFor(mc -> getZombie(mc) != null);
		double startZ = context.computeOnClient(mc -> getZombie(mc).getZ());
		
		runWurstCommand("setslider Backtrack min_delay 500");
		runWurstCommand("setslider Backtrack max_delay 500");
		runWurstCommand("setcheckbox Backtrack smart off");
		runWurstCommand("t Backtrack on");
		
		// like hitting it, without actually hurting it
		context.runOnClient(mc -> WurstClient.INSTANCE.getHax().backtrackHack
			.onPlayerAttacksEntity(getZombie(mc)));
		context.waitTicks(2);
		
		runCommand("tp @e[type=zombie] 5 -60 -11");
		context.waitTicks(5);
		double zDelayed = context.computeOnClient(mc -> getZombie(mc).getZ());
		if(Math.abs(zDelayed - startZ) > 0.1)
			throw new AssertionError(
				"Zombie moved before the delay was over, z=" + zDelayed);
		
		waitFor(mc -> Math.abs(getZombie(mc).getZ() - (startZ + 2)) < 0.1,
			"Zombie never moved to its real position");
		
		// Clean up
		runWurstCommand("t Backtrack off");
		runWurstCommand("setslider Backtrack min_delay 100");
		runWurstCommand("setslider Backtrack max_delay 200");
		runWurstCommand("setcheckbox Backtrack smart on");
		runCommand("kill @e[type=zombie]");
		context.waitFor(mc -> getZombie(mc) == null);
		runCommand("tp @s 0 -57 0 0 0");
		context.waitFor(mc -> mc.player.getY() == -57);
		clearParticles();
		clearChat();
	}
	
	private static Zombie getZombie(Minecraft mc)
	{
		for(var entity : mc.level.entitiesForRendering())
			if(entity instanceof Zombie zombie)
				return zombie;
			
		return null;
	}
}
