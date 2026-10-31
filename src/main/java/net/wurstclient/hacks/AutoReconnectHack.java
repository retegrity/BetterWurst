/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"auto reconnect", "AutoRejoin", "auto rejoin"})
@DontBlock
public final class AutoReconnectHack extends Hack
{
	private final SliderSetting waitTime =
		new SliderSetting("Wait time", "Time before reconnecting in seconds.",
			5, 0, 60, 0.5, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private final CheckboxSetting showButton = new CheckboxSetting(
		"AutoReconnect button",
		"Shows the \"AutoReconnect\" button on the screen you see when you"
			+ " get kicked, which turns AutoReconnect on or off and shows how"
			+ " long it waits.\n\n"
			+ "AutoReconnect still works when the button is hidden.",
		true);
	
	public AutoReconnectHack()
	{
		super("AutoReconnect");
		setCategory(Category.OTHER);
		addSetting(waitTime);
		addSetting(showButton);
	}
	
	public int getWaitTicks()
	{
		return (int)(waitTime.getValue() * 20);
	}
	
	public boolean isButtonShown()
	{
		return showButton.isChecked();
	}
	
	// See DisconnectedScreenMixin
}
