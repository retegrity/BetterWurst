/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ore blocks, and the stone they replace.
 */
public enum OreBlocks
{
	;
	
	private static final Map<Block, BlockState> BASE_STATES =
		new IdentityHashMap<>();
	
	static
	{
		for(Block block : BuiltInRegistries.BLOCK)
		{
			String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
			if(block == Blocks.ANCIENT_DEBRIS
				|| path.startsWith("nether_") && path.endsWith("_ore"))
				BASE_STATES.put(block, Blocks.NETHERRACK.defaultBlockState());
			else if(path.startsWith("deepslate_") && path.endsWith("_ore"))
				BASE_STATES.put(block, Blocks.DEEPSLATE.defaultBlockState());
			else if(path.endsWith("_ore"))
				BASE_STATES.put(block, Blocks.STONE.defaultBlockState());
		}
	}
	
	public static boolean isOre(Block block)
	{
		return BASE_STATES.containsKey(block);
	}
	
	public static boolean isOre(BlockState state)
	{
		return BASE_STATES.containsKey(state.getBlock());
	}
	
	/**
	 * The block that the ore replaced, so the world looks like it did before
	 * ores were generated. This also undoes fake ores from anti-xray plugins.
	 */
	public static BlockState getBaseState(BlockState state)
	{
		BlockState base = BASE_STATES.get(state.getBlock());
		return base != null ? base : state;
	}
	
	/**
	 * The deepslate variant of a normal ore, or the other way around, or null
	 * if there is none.
	 */
	public static Block getOtherVariant(Block ore)
	{
		Identifier id = BuiltInRegistries.BLOCK.getKey(ore);
		String path = id.getPath();
		String other = path.startsWith("deepslate_")
			? path.substring("deepslate_".length()) : "deepslate_" + path;
		
		return BuiltInRegistries.BLOCK
			.getOptional(
				Identifier.fromNamespaceAndPath(id.getNamespace(), other))
			.filter(OreBlocks::isOre).orElse(null);
	}
	
	/**
	 * The color that an ore is highlighted with, as 0xRRGGBB.
	 */
	public static int getColor(Block ore)
	{
		String path = BuiltInRegistries.BLOCK.getKey(ore).getPath();
		if(path.contains("diamond"))
			return 0x4AEDD9;
		if(path.contains("emerald"))
			return 0x17DD62;
		if(path.contains("gold"))
			return 0xFCEE4B;
		if(path.contains("iron"))
			return 0xD8AF93;
		if(path.contains("copper"))
			return 0xE0753A;
		if(path.contains("redstone"))
			return 0xFF2020;
		if(path.contains("lapis"))
			return 0x2B4FD9;
		if(path.contains("coal"))
			return 0x505050;
		if(path.contains("quartz"))
			return 0xEDE6DF;
		if(ore == Blocks.ANCIENT_DEBRIS)
			return 0x9A6A4E;
		
		return 0xFFFFFF;
	}
}
