/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.gui.Font;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.FontSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.CustomFont;

@SearchTags({"font", "fonts", "CustomFont", "custom font", "text"})
@DontBlock
public final class FontOtf extends OtherFeature
{
	private static final Logger LOGGER = LoggerFactory.getLogger("Wurst Font");
	
	private final FontSetting font = new FontSetting("Font",
		"The font of the text in Wurst's HackList, TabGUI, ClickGUI and"
			+ " Navigator.\n\n"
			+ "You can pick any font installed on your computer, or put your"
			+ " own .ttf/.otf fonts into the fonts folder (\"Open Folder\").");
	
	private final SliderSetting size = new SliderSetting("Size",
		"How big custom fonts are. Has no effect on the Minecraft font.", 10, 6,
		16, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting verticalOffset = new SliderSetting(
		"Vertical offset",
		"Moves the text of custom fonts up or down, for fonts that don't sit"
			+ " in the middle of their line.",
		0, -3, 3, 0.25, ValueDisplay.DECIMAL.withSuffix("px"));
	
	// the font for normal text, and a sharper copy for scaled-up text (the
	// text logo), so it isn't blurry
	private final LoadedFont normalFont = new LoadedFont();
	private final LoadedFont scaledFont = new LoadedFont();
	
	public FontOtf()
	{
		super("Font", "Changes the font that Wurst renders its text with.");
		addSetting(font);
		addSetting(size);
		addSetting(verticalOffset);
	}
	
	/**
	 * Returns the font that Wurst's own text should be rendered with. The
	 * custom font is (re)loaded when the settings or the GUI scale change.
	 */
	public Font getFont()
	{
		return normalFont.get(1);
	}
	
	/**
	 * Like {@link #getFont()}, but for text that is drawn scaled up by the
	 * given factor. Its glyphs are rendered at that higher resolution, so the
	 * text stays sharp instead of being a blurry, stretched copy.
	 */
	public Font getFont(float scale)
	{
		if(scale <= 1)
			return getFont();
		
		return scaledFont.get(scale);
	}
	
	private final class LoadedFont
	{
		private CustomFont customFont;
		private String loadedKey;
		
		public Font get(float scale)
		{
			Path selected = font.getSelected();
			if(selected == null)
			{
				unload();
				return MC.font;
			}
			
			// render the glyphs at the real resolution of the GUI (and of the
			// scaled text), so they are sharp at any GUI scale
			int guiScale = Math.max(1, MC.getWindow().getGuiScale());
			float oversample = guiScale * scale;
			String key = selected + "|" + size.getValue() + "|"
				+ verticalOffset.getValue() + "|" + oversample;
			
			if(!Objects.equals(key, loadedKey))
			{
				unload();
				loadedKey = key;
				
				try
				{
					customFont = CustomFont.load(selected, size.getValueF(),
						oversample, verticalOffset.getValueF());
					
				}catch(Exception e)
				{
					// keep the key, so it doesn't try again on every frame
					LOGGER.error("Couldn't load font {}", selected, e);
				}
			}
			
			return customFont != null ? customFont.getFont() : MC.font;
		}
		
		private void unload()
		{
			if(customFont != null)
			{
				customFont.close();
				customFont = null;
			}
			
			loadedKey = null;
		}
	}
}
