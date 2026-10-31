/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.worldgen.WorldgenManager;
import net.wurstclient.worldgen.WorldgenSeeds;

public final class SeedCmd extends Command
{
	public SeedCmd()
	{
		super("seed",
			"Sets the world seed of the server you are on, for .locate and"
				+ " .highlight. It's saved for each server.\n"
				+ "If the server uses worldgen datapacks (like Terralith), put"
				+ " the exact same versions into the server's folder"
				+ " (.seed folder).\n"
				+ "In singleplayer, the world's own seed and datapacks are"
				+ " used.",
			".seed", ".seed <seed>", ".seed clear", ".seed folder");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
		{
			showSeed();
			return;
		}
		
		String arg = String.join(" ", args);
		switch(arg.toLowerCase())
		{
			case "clear":
			requireServer();
			try
			{
				WorldgenSeeds.clearSeed();
				WorldgenManager.unload();
				ChatUtils.message("Seed removed.");
				
			}catch(IOException e)
			{
				throw new CmdError("Couldn't remove the seed: " + e);
			}
			break;
			
			case "folder":
			openFolder();
			break;
			
			default:
			setSeed(arg);
			break;
		}
	}
	
	private void showSeed() throws CmdException
	{
		OptionalLong seed = WorldgenSeeds.getSeed();
		if(WorldgenSeeds.isSingleplayer())
		{
			ChatUtils.message("Seed: " + seed.getAsLong() + " (this world's)");
			return;
		}
		
		requireServer();
		if(seed.isEmpty())
			ChatUtils.message("No seed set for this server. Use .seed <seed>.");
		else
			ChatUtils.message("Seed: " + seed.getAsLong());
	}
	
	private void setSeed(String text) throws CmdException
	{
		if(WorldgenSeeds.isSingleplayer())
			throw new CmdError(
				"In singleplayer, the world's own seed is used.");
		
		requireServer();
		OptionalLong seed = WorldOptions.parseSeed(text);
		if(seed.isEmpty())
			throw new CmdSyntaxError();
		
		try
		{
			WorldgenSeeds.setSeed(seed.getAsLong());
			
		}catch(IOException e)
		{
			throw new CmdError("Couldn't save the seed: " + e);
		}
		
		ChatUtils.message("Seed set to " + seed.getAsLong() + ".");
		
		// start loading it now, so .locate and .highlight are faster
		WorldgenManager.get();
	}
	
	private void openFolder() throws CmdException
	{
		Path folder = WorldgenSeeds.getDatapacksFolder();
		if(folder == null)
			throw new CmdError("You aren't in a world.");
		
		try
		{
			Files.createDirectories(folder);
			
		}catch(IOException e)
		{
			throw new CmdError("Couldn't create the folder: " + e);
		}
		
		Util.getPlatform().openPath(folder);
	}
	
	private void requireServer() throws CmdException
	{
		if(WorldgenSeeds.getServerFolder() == null)
			throw new CmdError("You aren't on a server.");
	}
}
