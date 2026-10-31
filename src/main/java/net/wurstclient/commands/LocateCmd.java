/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;
import net.wurstclient.worldgen.DimensionSim;
import net.wurstclient.worldgen.StructureLocator;
import net.wurstclient.worldgen.WorldgenData;
import net.wurstclient.worldgen.WorldgenManager;

public final class LocateCmd extends Command
{
	private CompletableFuture<?> running;
	private volatile boolean cancelled;
	
	public LocateCmd()
	{
		super("locate",
			"Finds the nearest structures from the world seed, like vanilla's"
				+ " /locate. Also works with worldgen datapacks like"
				+ " Terralith.\n"
				+ "Set the seed of the server with .seed first.",
			".locate <structure> [count]", ".locate cancel",
			"Example: .locate village_plains 3");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 1 && args[0].equalsIgnoreCase("cancel"))
		{
			cancel();
			return;
		}
		
		if(args.length < 1 || args.length > 2)
			throw new CmdSyntaxError();
		
		int count = 5;
		if(args.length == 2)
		{
			if(!MathUtils.isInteger(args[1]))
				throw new CmdSyntaxError("Count must be a number.");
			count = Integer.parseInt(args[1]);
			if(count < 1 || count > 50)
				throw new CmdError("Count must be between 1 and 50.");
		}
		
		if(running != null && !running.isDone())
			throw new CmdError(
				"Already locating something. Use .locate cancel to stop.");
		
		CompletableFuture<WorldgenData> data = WorldgenManager.get();
		if(data == null)
			throw new CmdError(
				"No seed set for this server. Use .seed <seed> first.");
		
		if(!data.isDone())
			ChatUtils.message(
				"Loading the world generation, this takes a few seconds...");
		
		String name = args[0];
		int wantedCount = count;
		ResourceKey<Level> dimension = MC.level.dimension();
		BlockPos from = MC.player.blockPosition();
		cancelled = false;
		
		running = data.thenApplyAsync(d -> {
			DimensionSim dim = d.getDimension(dimension);
			if(dim == null)
				throw new IllegalStateException(
					"This dimension isn't in the world generation.");
			
			Holder<Structure> structure = d.findStructure(name)
				.orElseThrow(() -> new IllegalArgumentException(
					"Unknown structure: " + name));
			
			return new StructureLocator(dim).locate(structure, from,
				wantedCount, () -> cancelled);
			
		}, WorldgenManager.EXECUTOR).whenCompleteAsync(
			(results, error) -> showResults(name, results, error), MC);
	}
	
	private void showResults(String name, List<StructureLocator.Result> results,
		Throwable error)
	{
		if(error != null)
		{
			Throwable cause =
				error instanceof CompletionException && error.getCause() != null
					? error.getCause() : error;
			ChatUtils.error("Couldn't locate " + name + ": "
				+ (cause.getMessage() != null ? cause.getMessage() : cause));
			return;
		}
		
		if(cancelled)
			return;
		
		if(results.isEmpty())
		{
			ChatUtils.message("No " + name + " found nearby.");
			return;
		}
		
		ChatUtils.message("Nearest " + name + ":");
		for(StructureLocator.Result result : results)
		{
			BlockPos pos = result.pos();
			String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
			String text = coords + " (" + (int)result.distance() + " blocks)";
			ChatUtils.component(Component.literal(text)
				.withStyle(s -> s.withUnderlined(true)
					.withClickEvent(new ClickEvent.CopyToClipboard(coords))
					.withHoverEvent(new HoverEvent.ShowText(
						Component.literal("Copy to clipboard")))));
		}
	}
	
	private void cancel()
	{
		if(running == null || running.isDone())
		{
			ChatUtils.message("Nothing to cancel.");
			return;
		}
		
		cancelled = true;
		ChatUtils.message("Cancelled.");
	}
}
