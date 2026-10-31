/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.awt.Color;
import java.util.function.BooleanSupplier;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"wurst logo", "top left corner"})
@DontBlock
public final class WurstLogoOtf extends OtherFeature
{
	private final ColorSetting bgColor = new ColorSetting("Background",
		"Background color.\n"
			+ "Only visible when \u00a76RainbowUI\u00a7r is disabled.",
		Color.WHITE);
	
	private final ColorSetting txtColor =
		new ColorSetting("Text", "Text color.", Color.BLACK);
	
	private final EnumSetting<Visibility> visibility =
		new EnumSetting<>("Visibility", Visibility.values(), Visibility.ALWAYS);
	
	private final CheckboxSetting onlyLogo =
		new CheckboxSetting("Only show the Wurst Logo",
			"Hides the background and the version text, so that only the Wurst"
				+ " logo itself is shown.",
			false);
	
	private final CheckboxSetting textLogo =
		new CheckboxSetting("Render WurstLogo in text",
			"Shows the logo as the text \"Wurst\" instead of the image, without"
				+ " the background and version strip.",
			false);
	
	private final SliderSetting textSize =
		new SliderSetting("Text size", "How big the text logo is.", 2, 1, 4,
			0.25, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final ColorSetting textLogoColor =
		new ColorSetting("Text logo color",
			"Color of the text logo.\n"
				+ "Only visible when \u00a76RainbowUI\u00a7r is disabled.",
			Color.WHITE);
	
	private final CheckboxSetting textSyncHackList = new CheckboxSetting(
		"Sync text logo with HackList",
		"Gives the text logo the same color as the HackList.\n\n"
			+ "If the HackList uses \u00a7lRainbow\u00a7r color mode, the text"
			+ " logo becomes the first step of the rainbow, as if it was one more"
			+ " entry above the list.",
		false);
	
	private final CheckboxSetting textCustomFont =
		new CheckboxSetting("Text logo uses custom font",
			"Renders the text logo with the font from the \u00a76Font\u00a7r"
				+ " settings instead of the Minecraft font.",
			false);
	
	private final CheckboxSetting textVersion = new CheckboxSetting(
		"Show version in text logo",
		"Appends the Wurst version to the text logo, e.g. \"Wurst 7.56.1\".",
		false);
	
	public WurstLogoOtf()
	{
		super("WurstLogo", "Shows the Wurst logo and version on the screen.");
		addSetting(bgColor);
		addSetting(txtColor);
		addSetting(visibility);
		addSetting(onlyLogo);
		addSetting(textLogo);
		addSetting(textSize);
		addSetting(textLogoColor);
		addSetting(textSyncHackList);
		addSetting(textCustomFont);
		addSetting(textVersion);
	}
	
	public boolean isTextLogo()
	{
		return textLogo.isChecked();
	}
	
	public float getTextSize()
	{
		return textSize.getValueF();
	}
	
	public int getTextLogoColor()
	{
		return textLogoColor.getColorI();
	}
	
	public boolean isTextSyncedWithHackList()
	{
		return textSyncHackList.isChecked();
	}
	
	public boolean isTextCustomFont()
	{
		return textCustomFont.isChecked();
	}
	
	public boolean isTextVersion()
	{
		return textVersion.isChecked();
	}
	
	/**
	 * The y coordinate just below the logo, for the HUD elements under it.
	 */
	public int getBottom()
	{
		if(!isTextLogo())
			return 21;
		
		// 2px margin, 9px line height, a little extra for the shadow
		return 2 + (int)Math.ceil(9 * getTextSize());
	}
	
	public boolean isVisible()
	{
		return visibility.getSelected().isVisible();
	}
	
	public boolean isOnlyLogo()
	{
		return onlyLogo.isChecked();
	}
	
	public int getBackgroundColor()
	{
		return bgColor.getColorI(128);
	}
	
	public int getTextColor()
	{
		return txtColor.getColorI();
	}
	
	public static enum Visibility
	{
		ALWAYS("Always", () -> true),
		
		ONLY_OUTDATED("Only when outdated",
			() -> WURST.getUpdater().isOutdated());
		
		private final String name;
		private final BooleanSupplier visible;
		
		private Visibility(String name, BooleanSupplier visible)
		{
			this.name = name;
			this.visible = visible;
		}
		
		public boolean isVisible()
		{
			return visible.getAsBoolean();
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
