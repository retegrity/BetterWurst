/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.block.Block;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.worldgen.OreBlocks;
import net.wurstclient.worldgen.WorldgenData;
import net.wurstclient.worldgen.WorldgenManager;

/**
 * Tab-completion for Wurst commands in the chat, shown in the same popup as
 * vanilla's command suggestions. The suggestions for each argument are read
 * from the command's syntax (e.g. ".t &lt;hack&gt; [on|off]").
 */
public enum CmdSuggestions
{
	;
	
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Minecraft MC = WurstClient.MC;
	
	/**
	 * Returns the suggestions for the given chat input (up to the cursor), or
	 * null if it isn't a Wurst command.
	 */
	public static CompletableFuture<Suggestions> getSuggestions(String input)
	{
		if(!isWurstCommand(input))
			return null;
		
		int argStart = input.lastIndexOf(' ') + 1;
		SuggestionsBuilder builder =
			new SuggestionsBuilder(input, Math.max(argStart, 1));
		
		// command name
		if(argStart == 0)
			return SharedSuggestionProvider.suggest(
				getCmds().map(cmd -> cmd.getName().substring(1)), builder);
		
		Command cmd = getCmd(input);
		if(cmd == null)
			return builder.buildFuture();
		
		// arguments
		String[] args = input.substring(input.indexOf(' ') + 1).split(" ", -1);
		LinkedHashSet<String> suggestions = new LinkedHashSet<>();
		for(String[] syntax : getSyntaxes(cmd))
			addSuggestions(suggestions, cmd, syntax, args);
		
		return SharedSuggestionProvider.suggest(suggestions, builder);
	}
	
	/**
	 * Returns the syntax of the typed command, shown when there is nothing to
	 * suggest.
	 */
	public static List<String> getUsage(String input)
	{
		if(!isWurstCommand(input) || input.indexOf(' ') == -1)
			return List.of();
		
		Command cmd = getCmd(input);
		if(cmd == null)
			return List.of();
		
		return getSyntaxes(cmd).stream().map(syntax -> String.join(" ", syntax))
			.toList();
	}
	
	public static boolean isWurstCommand(String input)
	{
		return WURST.isEnabled() && input.startsWith(".")
			&& !input.startsWith("..");
	}
	
	private static Command getCmd(String input)
	{
		String name = input.substring(1).split(" ")[0];
		return getCmds()
			.filter(cmd -> cmd.getName().substring(1).equalsIgnoreCase(name))
			.findFirst().orElse(null);
	}
	
	private static Stream<Command> getCmds()
	{
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		return WURST.getCmds().getAllCmds().stream().filter(
			cmd -> !tooManyHax.isEnabled() || !tooManyHax.isBlocked(cmd));
	}
	
	/**
	 * Returns the syntax lines of the command, starting with the command name,
	 * e.g. [".t", "&lt;hack&gt;", "[on|off]"]. Example lines and notes are
	 * left out.
	 */
	private static List<String[]> getSyntaxes(Command cmd)
	{
		ArrayList<String[]> syntaxes = new ArrayList<>();
		for(String line : cmd.getSyntax())
		{
			if(line.startsWith("Syntax: "))
				line = line.substring(8);
			
			// e.g. "Turn off: .annoy" or "List commands: .help [<page>]"
			int start = line.indexOf(cmd.getName());
			if(start == -1 || line.startsWith("Example")
				|| start > 0 && !line.substring(0, start).endsWith(": "))
				continue;
			
			String syntax = line.substring(start);
			if(!syntax.equals(cmd.getName())
				&& !syntax.startsWith(cmd.getName() + " "))
				continue;
			
			syntaxes.add(syntax.split(" "));
		}
		
		return syntaxes;
	}
	
	/**
	 * Adds the suggestions for the last argument, if the syntax matches the
	 * arguments before it.
	 */
	private static void addSuggestions(LinkedHashSet<String> suggestions,
		Command cmd, String[] syntax, String[] args)
	{
		int index = args.length - 1;
		if(index + 1 >= syntax.length)
			return;
		
		for(int i = 0; i < index; i++)
		{
			String token = syntax[i + 1];
			if(isPlaceholder(token))
				continue;
			
			if(!getAlternatives(token).contains(args[i].toLowerCase()))
				return;
		}
		
		String token = syntax[index + 1];
		if(!isPlaceholder(token))
		{
			suggestions.addAll(getAlternatives(token));
			return;
		}
		
		String type = token.replaceAll("[<>\\[\\]]", "").toLowerCase();
		switch(type)
		{
			case "hack", "hacks":
			WURST.getHax().getAllHax()
				.forEach(hack -> suggestions.add(hack.getName()));
			break;
			
			case "feature":
			WURST.getNavigator().getList()
				.forEach(feature -> suggestions.add(feature.getName()));
			break;
			
			case "command", "commands":
			getCmds().forEach(c -> suggestions.add(c.getName().substring(1)));
			break;
			
			case "setting":
			Feature feature = findFeature(args[index - 1]);
			if(feature != null)
				for(Setting setting : feature.getSettings().values())
					if(isSettingFor(cmd, setting))
						suggestions.add(setting.getName().replace(" ", "_"));
			break;
			
			case "mode":
			if(findSetting(args[index - 2],
				args[index - 1]) instanceof EnumSetting<?> enumSetting)
				for(Object mode : enumSetting.getValues())
					suggestions.add(mode.toString().replace(" ", "_"));
			suggestions.add("next");
			suggestions.add("prev");
			break;
			
			case "value":
			if(findSetting(args[index - 2],
				args[index - 1]) instanceof SliderSetting slider)
			{
				// the current value, as a number that .setslider accepts
				double value = slider.getValue();
				suggestions.add(value == Math.rint(value)
					? Long.toString((long)value) : Double.toString(value));
			}
			break;
			
			case "player", "entity", "name":
			if(MC.getConnection() != null)
				for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
					suggestions
						.add(StringUtil.stripColor(info.getProfile().name()));
			break;
			
			case "block":
			BuiltInRegistries.BLOCK.keySet()
				.forEach(id -> suggestions.add(shortId(id)));
			break;
			
			case "item":
			BuiltInRegistries.ITEM.keySet()
				.forEach(id -> suggestions.add(shortId(id)));
			break;
			
			case "ore":
			for(Block block : BuiltInRegistries.BLOCK)
				if(OreBlocks.isOre(block))
					suggestions
						.add(shortId(BuiltInRegistries.BLOCK.getKey(block)));
			break;
			
			case "structure":
			// starts loading the world generation if it isn't loaded yet,
			// so the structures can be suggested soon
			WorldgenData worldgen = WorldgenManager.getIfLoaded();
			if(worldgen == null)
				WorldgenManager.get();
			else
				suggestions.addAll(worldgen.getStructureNames());
			break;
			
			case "gamemode":
			suggestions.addAll(
				List.of("survival", "creative", "adventure", "spectator"));
			break;
		}
	}
	
	private static boolean isPlaceholder(String token)
	{
		return token.contains("<");
	}
	
	/**
	 * Returns the words that a literal syntax token allows, e.g. "add", or
	 * "on" and "off" for "(on|off)".
	 */
	private static List<String> getAlternatives(String token)
	{
		return Arrays.stream(token.replaceAll("[()\\[\\]]", "").split("\\|"))
			.map(s -> s.toLowerCase(Locale.ROOT)).toList();
	}
	
	private static boolean isSettingFor(Command cmd, Setting setting)
	{
		return switch(cmd.getName())
		{
			case ".setcheckbox" -> setting instanceof CheckboxSetting;
			case ".setslider" -> setting instanceof SliderSetting;
			case ".setmode" -> setting instanceof EnumSetting<?>;
			case ".setcolor" -> setting instanceof ColorSetting;
			case ".setblock" -> setting instanceof BlockSetting;
			case ".blocklist" -> setting instanceof BlockListSetting;
			case ".itemlist" -> setting instanceof ItemListSetting;
			default -> true;
		};
	}
	
	private static Feature findFeature(String name)
	{
		return WURST.getNavigator().getList().stream()
			.filter(f -> f.getName().equalsIgnoreCase(name)).findFirst()
			.orElse(null);
	}
	
	private static Setting findSetting(String featureName, String settingName)
	{
		Feature feature = findFeature(featureName);
		if(feature == null)
			return null;
		
		return feature.getSettings()
			.get(settingName.replace("_", " ").toLowerCase());
	}
	
	private static String shortId(Identifier id)
	{
		return id.getNamespace().equals("minecraft") ? id.getPath()
			: id.toString();
	}
}
