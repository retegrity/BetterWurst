/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.worldgen.DimensionSim;
import net.wurstclient.worldgen.OreBlocks;
import net.wurstclient.worldgen.OreSimulator;
import net.wurstclient.worldgen.WorldgenData;
import net.wurstclient.worldgen.WorldgenManager;

public final class HighlightCmd extends Command
	implements UpdateListener, RenderListener
{
	private final Map<BlockPos, Block> found = new ConcurrentHashMap<>();
	private ResourceKey<Level> dimension;
	private List<ColoredBox> boxes = List.of();
	private volatile boolean changed;
	
	// increased by .highlight clear, so searches that are still running
	// don't add their results afterwards
	private final AtomicInteger generation = new AtomicInteger();
	
	public HighlightCmd()
	{
		super("highlight",
			"Highlights the ores that the world seed generates around you."
				+ " Also works with worldgen datapacks like Terralith.\n"
				+ "Set the seed of the server with .seed first. The deepslate"
				+ " variant is highlighted too.\n"
				+ "Most accurate in loaded chunks, since it uses their caves."
				+ " Areas that players have dug out can be off.",
			".highlight <ore> [radius]", ".highlight clear",
			"Radius is in chunks, 8 by default.",
			"Example: .highlight diamond_ore 6");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 1 && args[0].equalsIgnoreCase("clear"))
		{
			clear();
			ChatUtils.message("Highlights cleared.");
			return;
		}
		
		if(args.length < 1 || args.length > 2)
			throw new CmdSyntaxError();
		
		Block ore = parseOre(args[0]);
		int radius = 8;
		if(args.length == 2)
		{
			if(!MathUtils.isInteger(args[1]))
				throw new CmdSyntaxError("Radius must be a number.");
			radius = Integer.parseInt(args[1]);
			if(radius < 1 || radius > 32)
				throw new CmdError("Radius must be between 1 and 32.");
		}
		
		CompletableFuture<WorldgenData> data = WorldgenManager.get();
		if(data == null)
			throw new CmdError(
				"No seed set for this server. Use .seed <seed> first.");
		
		if(!data.isDone())
			ChatUtils.message(
				"Loading the world generation, this takes a few seconds...");
		
		int chunkRadius = radius;
		data.whenCompleteAsync((d, error) -> {
			if(error != null)
			{
				Throwable cause = error instanceof CompletionException
					&& error.getCause() != null ? error.getCause() : error;
				ChatUtils.error("Couldn't load the world generation: "
					+ (cause.getMessage() != null ? cause.getMessage()
						: cause));
				return;
			}
			
			start(d, ore, chunkRadius);
		}, MC);
	}
	
	private Block parseOre(String name) throws CmdError
	{
		Identifier id = Identifier.tryParse(name);
		Block block = id == null ? null
			: BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
		if(block == null || !OreBlocks.isOre(block))
			throw new CmdError("\"" + name + "\" isn't an ore.");
		
		return block;
	}
	
	/**
	 * Starts the search. Runs on the main thread.
	 */
	private void start(WorldgenData data, Block ore, int radius)
	{
		if(MC.level == null || MC.player == null)
			return;
		
		DimensionSim dim = WorldgenManager.getDimension(data);
		if(dim == null)
		{
			ChatUtils.error("This dimension isn't in the world generation.");
			return;
		}
		
		Set<Block> wanted = new LinkedHashSet<>();
		wanted.add(ore);
		Block variant = OreBlocks.getOtherVariant(ore);
		if(variant != null)
			wanted.add(variant);
		wanted.retainAll(dim.getOreBlocks());
		if(wanted.isEmpty())
		{
			ChatUtils.error(BuiltInRegistries.BLOCK.getKey(ore).getPath()
				+ " doesn't generate in this dimension.");
			return;
		}
		
		if(dimension != MC.level.dimension())
			clear();
		dimension = MC.level.dimension();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		
		// the client's chunks are only read here, on the main thread
		ChunkPos center = MC.player.chunkPosition();
		Map<Long, LevelChunk> clientChunks = new HashMap<>();
		for(int x = center.x() - radius - 1; x <= center.x() + radius + 1; x++)
			for(int z = center.z() - radius - 1; z <= center.z() + radius
				+ 1; z++)
			{
				LevelChunk chunk = MC.level.getChunkSource().getChunk(x, z,
					ChunkStatus.FULL, false);
				if(chunk != null)
					clientChunks.put(ChunkPos.pack(x, z), chunk);
			}
			
		// only chunks whose neighbors are loaded too, since it needs their
		// caves and terrain
		OreSimulator simulator = new OreSimulator(dim, clientChunks);
		List<ChunkPos> centers = new ArrayList<>();
		for(int x = center.x() - radius; x <= center.x() + radius; x++)
			for(int z = center.z() - radius; z <= center.z() + radius; z++)
			{
				ChunkPos pos = new ChunkPos(x, z);
				if(simulator.isLoadedAround(pos))
					centers.add(pos);
			}
		
		int skipped = (2 * radius + 1) * (2 * radius + 1) - centers.size();
		if(centers.isEmpty())
		{
			ChatUtils.error("Those chunks aren't loaded.");
			return;
		}
		
		int gen = generation.get();
		AtomicInteger remaining = new AtomicInteger(centers.size());
		AtomicInteger count = new AtomicInteger();
		String names = wanted.stream()
			.map(b -> BuiltInRegistries.BLOCK.getKey(b).getPath())
			.collect(Collectors.joining(", "));
		ChatUtils.message("Searching " + centers.size() + " chunks for " + names
			+ "..."
			+ (skipped > 0
				? " (skipped " + skipped + " chunks that aren't fully loaded)"
				: ""));
		
		for(ChunkPos pos : centers)
			WorldgenManager.EXECUTOR.execute(() -> {
				if(generation.get() == gen)
				{
					Map<BlockPos, Block> result =
						simulator.simulate(pos, wanted);
					if(generation.get() == gen)
					{
						for(Map.Entry<BlockPos, Block> e : result.entrySet())
							if(found.putIfAbsent(e.getKey(),
								e.getValue()) == null)
								count.incrementAndGet();
						changed = true;
					}
				}
				
				if(remaining.decrementAndGet() == 0 && generation.get() == gen)
					MC.execute(() -> ChatUtils.message(
						"Found " + count.get() + " blocks of " + names + "."));
			});
	}
	
	private void clear()
	{
		generation.incrementAndGet();
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		found.clear();
		boxes = List.of();
		dimension = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.level.dimension() != dimension)
		{
			clear();
			return;
		}
		
		if(!changed)
			return;
		
		changed = false;
		List<ColoredBox> newBoxes = new ArrayList<>(found.size());
		for(Map.Entry<BlockPos, Block> e : found.entrySet())
			newBoxes.add(new ColoredBox(new AABB(e.getKey()),
				0xFF000000 | OreBlocks.getColor(e.getValue())));
		boxes = newBoxes;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		RenderUtils.drawOutlinedBoxes(matrixStack, boxes, false);
	}
}
