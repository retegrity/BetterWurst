/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

public final class StepHack extends Hack
{
	private final SliderSetting height = new SliderSetting("Height",
		"How many blocks you can step up.", 1, 1, 10, 1, ValueDisplay.INTEGER);
	
	public StepHack()
	{
		super("Step");
		setCategory(Category.MOVEMENT);
		addSetting(height);
	}
	
	public float adjustStepHeight(float stepHeight)
	{
		if(isEnabled())
			return height.getValueF();
		
		return stepHeight;
	}
	
	public boolean isAutoJumpAllowed()
	{
		return !isEnabled() && !WURST.getCmds().goToCmd.isActive();
	}
	
	// See LocalPlayerMixin.maxUpStep()
}
