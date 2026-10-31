/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import net.minecraft.client.multiplayer.ClientLevel;
import net.wurstclient.WurstClient;
import net.wurstclient.util.MinPriorityThreadFactory;

/**
 * Keeps the {@link WorldgenData} of the current world loaded, and loads it
 * again when the seed or the datapacks change.
 */
public enum WorldgenManager
{
	;
	
	/**
	 * Runs the loading and the searches, so they don't slow down the game.
	 */
	public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
		Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
		new MinPriorityThreadFactory());
	
	private static String loadedKey;
	private static CompletableFuture<WorldgenData> future;
	
	/**
	 * The world generation of the current world, loading it in the
	 * background if it isn't loaded yet. Call it on the main thread.
	 *
	 * @return null if the seed isn't known
	 */
	public static synchronized CompletableFuture<WorldgenData> get()
	{
		OptionalLong seed = WorldgenSeeds.getSeed();
		Path packs = WorldgenSeeds.getDatapacksFolder();
		if(seed.isEmpty() || packs == null)
			return null;
		
		String key = seed.getAsLong() + "|" + packs + "|" + listFiles(packs);
		if(key.equals(loadedKey) && future != null
			&& !future.isCompletedExceptionally())
			return future;
		
		unload();
		loadedKey = key;
		long seedValue = seed.getAsLong();
		future = CompletableFuture.supplyAsync(() -> {
			try
			{
				return WorldgenData.load(seedValue, packs);
				
			}catch(IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}, EXECUTOR);
		return future;
	}
	
	/**
	 * The world generation if it's already loaded for the current world,
	 * otherwise null. Doesn't start loading it.
	 */
	public static synchronized WorldgenData getIfLoaded()
	{
		if(future == null || !future.isDone()
			|| future.isCompletedExceptionally())
			return null;
		
		return future.join();
	}
	
	/**
	 * The world generation of the dimension the player is in.
	 */
	public static DimensionSim getDimension(WorldgenData data)
	{
		ClientLevel level = WurstClient.MC.level;
		return level == null ? null : data.getDimension(level.dimension());
	}
	
	public static synchronized void unload()
	{
		if(future != null)
			future.thenAccept(WorldgenData::close);
		
		future = null;
		loadedKey = null;
	}
	
	/**
	 * The names, sizes and dates of the datapacks, so it notices when they
	 * change.
	 */
	private static String listFiles(Path folder)
	{
		if(!Files.isDirectory(folder))
			return "";
		
		try(Stream<Path> files = Files.list(folder))
		{
			StringBuilder sb = new StringBuilder();
			files.sorted().forEach(file -> {
				sb.append(file.getFileName());
				try
				{
					sb.append(':').append(Files.size(file)).append(':')
						.append(Files.getLastModifiedTime(file).toMillis());
				}catch(IOException e)
				{
					// still list its name
				}
				sb.append(';');
			});
			return sb.toString();
			
		}catch(IOException e)
		{
			return "";
		}
	}
}
