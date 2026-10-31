/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.components.FontComponent;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;
import net.wurstclient.util.text.WText;

/**
 * Lets you pick a TrueType/OpenType font file, either from Wurst's "fonts"
 * folder (where you can put your own fonts) or from the fonts installed on
 * your system. No font file means the vanilla Minecraft font.
 */
public final class FontSetting extends Setting
{
	public static final String MINECRAFT_FONT = "Minecraft";
	
	private final Path folder;
	
	// null for the Minecraft font
	private Path selected;
	
	public FontSetting(String name, String description)
	{
		super(name, WText.literal(description));
		folder = WurstClient.INSTANCE.getWurstFolder().resolve("fonts");
		
		try
		{
			Files.createDirectories(folder);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	public Path getFolder()
	{
		return folder;
	}
	
	/**
	 * Returns the selected font file, or null for the Minecraft font.
	 */
	public Path getSelected()
	{
		return selected;
	}
	
	public String getSelectedName()
	{
		return getFontName(selected);
	}
	
	public static String getFontName(Path font)
	{
		if(font == null)
			return MINECRAFT_FONT;
		
		String fileName = "" + font.getFileName();
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}
	
	public void setSelected(Path font)
	{
		selected = font;
		WurstClient.INSTANCE.saveSettings();
	}
	
	/**
	 * Lists the fonts in Wurst's "fonts" folder first, then the fonts
	 * installed on the system, each sorted by name.
	 */
	public List<Path> listFonts()
	{
		ArrayList<Path> fonts = new ArrayList<>(findFonts(List.of(folder)));
		for(Path font : findFonts(getSystemFontFolders()))
			if(!fonts.contains(font))
				fonts.add(font);
			
		return fonts;
	}
	
	public boolean isInFontsFolder(Path font)
	{
		return font.startsWith(folder);
	}
	
	private static List<Path> findFonts(List<Path> folders)
	{
		ArrayList<Path> fonts = new ArrayList<>();
		for(Path dir : folders)
		{
			if(!Files.isDirectory(dir))
				continue;
			
			try(Stream<Path> files = Files.walk(dir, 4))
			{
				files.filter(Files::isRegularFile)
					.filter(FontSetting::isFontFile).forEach(fonts::add);
				
			}catch(IOException | RuntimeException e)
			{
				// unreadable folder, skip it
			}
		}
		
		fonts.sort(Comparator.comparing(FontSetting::getFontName,
			String.CASE_INSENSITIVE_ORDER));
		return fonts;
	}
	
	private static boolean isFontFile(Path path)
	{
		String name = ("" + path.getFileName()).toLowerCase(Locale.ROOT);
		return name.endsWith(".ttf") || name.endsWith(".otf");
	}
	
	private static List<Path> getSystemFontFolders()
	{
		ArrayList<Path> folders = new ArrayList<>();
		String home = System.getProperty("user.home");
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		
		if(os.contains("win"))
		{
			String windir = System.getenv("WINDIR");
			folders.add(
				Paths.get(windir != null ? windir : "C:\\Windows", "Fonts"));
			String localAppData = System.getenv("LOCALAPPDATA");
			if(localAppData != null)
				folders.add(
					Paths.get(localAppData, "Microsoft", "Windows", "Fonts"));
			
		}else if(os.contains("mac"))
		{
			folders.add(Paths.get("/System/Library/Fonts"));
			folders.add(Paths.get("/Library/Fonts"));
			folders.add(Paths.get(home, "Library", "Fonts"));
			
		}else
		{
			folders.add(Paths.get("/usr/share/fonts"));
			folders.add(Paths.get("/usr/local/share/fonts"));
			folders.add(Paths.get(home, ".local", "share", "fonts"));
			folders.add(Paths.get(home, ".fonts"));
		}
		
		return folders;
	}
	
	@Override
	public Component getComponent()
	{
		return new FontComponent(this);
	}
	
	@Override
	public void resetToDefault()
	{
		selected = null;
		WurstClient.INSTANCE.saveSettings();
	}
	
	@Override
	public void fromJson(JsonElement json)
	{
		try
		{
			String value = JsonUtils.getAsString(json);
			if(value.isEmpty())
			{
				selected = null;
				return;
			}
			
			// fonts in the fonts folder are saved relative to it
			Path font = folder.resolve(value);
			if(!Files.isRegularFile(font))
				throw new JsonException("Font not found: " + value);
			
			selected = font;
			
		}catch(JsonException e)
		{
			e.printStackTrace();
			selected = null;
		}
	}
	
	@Override
	public JsonElement toJson()
	{
		if(selected == null)
			return new JsonPrimitive("");
		
		if(isInFontsFolder(selected))
			return new JsonPrimitive("" + folder.relativize(selected));
		
		return new JsonPrimitive("" + selected.toAbsolutePath());
	}
	
	@Override
	public JsonObject exportWikiData()
	{
		JsonObject json = new JsonObject();
		json.addProperty("name", getName());
		json.addProperty("description", getDescription());
		json.addProperty("type", "Font");
		return json;
	}
	
	@Override
	public Set<PossibleKeybind> getPossibleKeybinds(String featureName)
	{
		return new LinkedHashSet<>();
	}
}
