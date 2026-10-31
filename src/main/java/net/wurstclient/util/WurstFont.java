/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.gui.Font;
import net.wurstclient.WurstClient;
import net.wurstclient.other_feature.OtfList;

public enum WurstFont
{
	;
	
	/**
	 * Returns the font that Wurst's own text (HackList, TabGUI, ClickGUI,
	 * Navigator) should be rendered with, as set in the Font feature.
	 */
	public static Font get()
	{
		OtfList otfs = WurstClient.INSTANCE.getOtfs();
		if(otfs == null)
			return WurstClient.MC.font;
		
		return otfs.fontOtf.getFont();
	}
}
