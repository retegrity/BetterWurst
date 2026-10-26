/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.level.GameType;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

public final class FakeGmCmd extends Command
{
	public FakeGmCmd()
	{
		super("fakegm",
			"Changes your game mode only on your side. The server still uses"
				+ " your real one, so most things you do won't work and"
				+ " creative items will be ghost items, unless the server"
				+ " doesn't check.",
			".fakegm <gamemode>", ".fakegm reset");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 1)
			throw new CmdSyntaxError();
		
		GameType mode = args[0].equalsIgnoreCase("reset") ? getRealMode()
			: parseMode(args[0].toLowerCase());
		
		MC.gameMode.setLocalMode(mode);
		ChatUtils.message("Fake game mode: " + mode.getName());
	}
	
	private GameType parseMode(String name) throws CmdSyntaxError
	{
		switch(name)
		{
			case "s":
			case "0":
			return GameType.SURVIVAL;
			
			case "c":
			case "1":
			return GameType.CREATIVE;
			
			case "a":
			case "2":
			return GameType.ADVENTURE;
			
			case "sp":
			case "3":
			return GameType.SPECTATOR;
		}
		
		GameType mode = GameType.byName(name, null);
		if(mode == null)
			throw new CmdSyntaxError("Unknown game mode: " + name);
		
		return mode;
	}
	
	private GameType getRealMode() throws CmdError
	{
		PlayerInfo info = MC.getConnection().getPlayerInfo(MC.player.getUUID());
		if(info == null)
			throw new CmdError("Couldn't find your real game mode.");
		
		return info.getGameMode();
	}
}
