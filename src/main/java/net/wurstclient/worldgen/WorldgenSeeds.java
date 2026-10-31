/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.wurstclient.WurstClient;

/**
 * Where the world seed and the worldgen datapacks of the current server are
 * stored: .minecraft/wurst/worldgen/&lt;server address&gt;/, with the seed in
 * seed.txt and the datapacks (zips or folders) next to it.
 *
 * <p>
 * In singleplayer, the world's own seed and datapacks folder are used.
 */
public enum WorldgenSeeds
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	private static final String SEED_FILE = "seed.txt";
	
	/**
	 * The seed of the current world, or empty if it isn't known.
	 */
	public static OptionalLong getSeed()
	{
		IntegratedServer server = MC.getSingleplayerServer();
		if(server != null)
			return OptionalLong
				.of(server.getWorldGenSettings().options().seed());
		
		Path file = getServerFolder();
		if(file == null)
			return OptionalLong.empty();
		
		try
		{
			String text = Files
				.readString(file.resolve(SEED_FILE), StandardCharsets.UTF_8)
				.trim();
			return OptionalLong.of(Long.parseLong(text));
			
		}catch(IOException | NumberFormatException e)
		{
			return OptionalLong.empty();
		}
	}
	
	public static void setSeed(long seed) throws IOException
	{
		Path folder = getServerFolder();
		if(folder == null)
			throw new IOException("Not on a server");
		
		Files.createDirectories(folder);
		Files.writeString(folder.resolve(SEED_FILE), Long.toString(seed),
			StandardCharsets.UTF_8);
	}
	
	public static void clearSeed() throws IOException
	{
		Path folder = getServerFolder();
		if(folder != null)
			Files.deleteIfExists(folder.resolve(SEED_FILE));
	}
	
	/**
	 * The folder with the worldgen datapacks of the current world, or null if
	 * not in a world.
	 */
	public static Path getDatapacksFolder()
	{
		IntegratedServer server = MC.getSingleplayerServer();
		if(server != null)
			return server.getWorldPath(LevelResource.DATAPACK_DIR);
		
		return getServerFolder();
	}
	
	/**
	 * .minecraft/wurst/worldgen/&lt;server address&gt;, or null when not on a
	 * server.
	 */
	public static Path getServerFolder()
	{
		ServerData server = MC.getCurrentServer();
		if(server == null || MC.getSingleplayerServer() != null)
			return null;
		
		String name =
			server.ip.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return WurstClient.INSTANCE.getWurstFolder().resolve("worldgen")
			.resolve(name);
	}
	
	public static boolean isSingleplayer()
	{
		return MC.getSingleplayerServer() != null;
	}
}
