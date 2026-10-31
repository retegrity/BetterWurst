/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings;

import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.Component;
import net.wurstclient.keybinds.PossibleKeybind;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.WurstFont;
import net.wurstclient.util.text.WText;

public abstract class Setting
{
	private final String name;
	private final WText description;
	
	public Setting(String name, WText description)
	{
		this.name = Objects.requireNonNull(name);
		this.description = Objects.requireNonNull(description);
	}
	
	public final String getName()
	{
		return name;
	}
	
	public final String getDescription()
	{
		return description.toString();
	}
	
	/**
	 * The description, wrapped to the given width. Descriptions that would
	 * be taller than the screen are wrapped wider instead, so their tooltip
	 * fits.
	 */
	public final String getWrappedDescription(int width)
	{
		String wrapped = ChatUtils.wrapText(getDescription(), width);
		int maxLines = (WurstClient.MC.getWindow().getGuiScaledHeight() - 8)
			/ WurstFont.get().lineHeight;
		
		while(wrapped.split("\n").length > maxLines && width < 400)
		{
			width += 50;
			wrapped = ChatUtils.wrapText(getDescription(), width);
		}
		
		return wrapped;
	}
	
	public abstract Component getComponent();
	
	public abstract void resetToDefault();
	
	public abstract void fromJson(JsonElement json);
	
	public abstract JsonElement toJson();
	
	/**
	 * Exports this setting's data to a {@link JsonObject} for use in the
	 * Wurst Wiki. Must always specify the following properties:
	 * <ul>
	 * <li>name
	 * <li>description
	 * <li>type
	 * </ul>
	 */
	public abstract JsonObject exportWikiData();
	
	public void update()
	{
		
	}
	
	public abstract Set<PossibleKeybind> getPossibleKeybinds(
		String featureName);
}
