/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.awt.Color;
import java.util.Comparator;

import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.WurstFont;

@SearchTags({"hack list", "HakList", "hak list", "HacksList", "hacks list",
	"HaxList", "hax list", "ArrayList", "array list", "ModList", "mod list",
	"CheatList", "cheat list"})
@DontBlock
public final class HackListOtf extends OtherFeature
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lAuto\u00a7r mode renders the whole list if it fits onto the screen.\n"
			+ "\u00a7lCount\u00a7r mode only renders the number of active hacks.\n"
			+ "\u00a7lHidden\u00a7r mode renders nothing.",
		Mode.values(), Mode.AUTO);
	
	private final EnumSetting<Position> position = new EnumSetting<>("Position",
		"Which side of the screen the HackList should be shown on."
			+ "\nChange this to \u00a7lRight\u00a7r when using TabGUI.",
		Position.values(), Position.LEFT);
	
	private final SliderSetting horizontalOffset =
		new SliderSetting("Horizontal offset",
			"Moves the HackList away from the side of the screen it's on.", 0,
			0, 10, 1, ValueDisplay.INTEGER.withSuffix("px"));
	
	private final SliderSetting verticalOffset =
		new SliderSetting("Vertical offset", "Moves the HackList down.", 0, 0,
			10, 1, ValueDisplay.INTEGER.withSuffix("px"));
	
	private final ColorSetting color = new ColorSetting("Color",
		"Color of the HackList text.\n"
			+ "Only visible when \u00a76RainbowUI\u00a7r is disabled.",
		Color.WHITE);
	
	private final EnumSetting<ColorMode> colorMode = new EnumSetting<>(
		"Color mode",
		"\u00a7lStatic\u00a7r uses the color above (or \u00a76RainbowUI\u00a7r"
			+ " if it's enabled).\n\n"
			+ "\u00a7lRainbow\u00a7r gives every entry its own color, so a"
			+ " rainbow flows down the list.",
		ColorMode.values(), ColorMode.STATIC);
	
	private final SliderSetting rainbowSpeed =
		new SliderSetting("Rainbow speed", "How fast the rainbow moves.", 0.05,
			0.01, 0.2, 0.005, ValueDisplay.DECIMAL);
	
	private final SliderSetting rainbowSpread =
		new SliderSetting("Rainbow spread",
			"How much the color changes from one entry to the next.", 0.01,
			0.001, 0.05, 0.001, ValueDisplay.DECIMAL);
	
	private final SliderSetting rainbowSaturation = new SliderSetting(
		"Rainbow saturation", 1, 0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting background = new CheckboxSetting("Background",
		"Draws a darkened background behind the entries.", false);
	
	private final ColorSetting backgroundColor =
		new ColorSetting("Background color", new Color(25, 25, 25));
	
	private final SliderSetting backgroundOpacity = new SliderSetting(
		"Background opacity", 0.5, 0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting outline = new CheckboxSetting("Outline",
		"Draws an outline around the entries.", false);
	
	private final SliderSetting outlineWidth = new SliderSetting(
		"Outline width", 1, 1, 5, 1, ValueDisplay.INTEGER.withSuffix("px"));
	
	private final ColorSetting outlineColor = new ColorSetting("Outline color",
		"Color of the outline. In \u00a7lRainbow\u00a7r color mode, the"
			+ " outline follows the rainbow instead.",
		Color.WHITE);
	
	private final EnumSetting<SortBy> sortBy = new EnumSetting<>("Sort by",
		"Determines how the HackList entries are sorted.\n"
			+ "Only visible when \u00a76Mode\u00a7r is set to \u00a76Auto\u00a7r.",
		SortBy.values(), SortBy.NAME);
	
	private final CheckboxSetting revSort =
		new CheckboxSetting("Reverse sorting", false);
	
	private final CheckboxSetting animations = new CheckboxSetting("Animations",
		"When enabled, entries slide into and out of the HackList as hacks are enabled and disabled.",
		true);
	
	private SortBy prevSortBy;
	private Boolean prevRevSort;
	
	public HackListOtf()
	{
		super("HackList", "Shows a list of active hacks on the screen.");
		
		addSetting(mode);
		addSetting(position);
		addSetting(horizontalOffset);
		addSetting(verticalOffset);
		addSetting(color);
		addSetting(colorMode);
		addSetting(rainbowSpeed);
		addSetting(rainbowSpread);
		addSetting(rainbowSaturation);
		addSetting(background);
		addSetting(backgroundColor);
		addSetting(backgroundOpacity);
		addSetting(outline);
		addSetting(outlineWidth);
		addSetting(outlineColor);
		addSetting(sortBy);
		addSetting(revSort);
		addSetting(animations);
	}
	
	public Mode getMode()
	{
		return mode.getSelected();
	}
	
	public Position getPosition()
	{
		return position.getSelected();
	}
	
	public int getHorizontalOffset()
	{
		return horizontalOffset.getValueI();
	}
	
	public int getVerticalOffset()
	{
		return verticalOffset.getValueI();
	}
	
	public boolean isAnimations()
	{
		return animations.isChecked();
	}
	
	public Comparator<Hack> getComparator()
	{
		if(revSort.isChecked())
			return sortBy.getSelected().comparator.reversed();
		
		return sortBy.getSelected().comparator;
	}
	
	public boolean shouldSort()
	{
		try
		{
			// width of a renderName could change at any time
			// must sort the HackList every tick
			if(sortBy.getSelected() == SortBy.WIDTH)
				return true;
			
			if(sortBy.getSelected() != prevSortBy)
				return true;
			
			if(!Boolean.valueOf(revSort.isChecked()).equals(prevRevSort))
				return true;
			
			return false;
			
		}finally
		{
			prevSortBy = sortBy.getSelected();
			prevRevSort = revSort.isChecked();
		}
	}
	
	public int getColor(int alpha)
	{
		return color.getColorI(alpha);
	}
	
	public boolean isRainbow()
	{
		return colorMode.getSelected() == ColorMode.RAINBOW;
	}
	
	/**
	 * Returns the rainbow color (RGB) of the given entry at the given time,
	 * like Meteor's active modules list.
	 */
	public int getRainbowColor(double seconds, int index)
	{
		double hue = seconds * rainbowSpeed.getValue()
			+ index * rainbowSpread.getValue();
		return Color.HSBtoRGB((float)(hue % 1), rainbowSaturation.getValueF(),
			1) & 0xFFFFFF;
	}
	
	public boolean hasBackground()
	{
		return background.isChecked();
	}
	
	public int getBackgroundColor()
	{
		return backgroundColor.getColorI(backgroundOpacity.getValueF());
	}
	
	public boolean hasOutline()
	{
		return outline.isChecked();
	}
	
	public int getOutlineWidth()
	{
		return outlineWidth.getValueI();
	}
	
	public int getOutlineColor()
	{
		return outlineColor.getColorI(1F);
	}
	
	public static enum ColorMode
	{
		STATIC("Static"),
		RAINBOW("Rainbow");
		
		private final String name;
		
		private ColorMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum Mode
	{
		AUTO("Auto"),
		
		COUNT("Count"),
		
		HIDDEN("Hidden");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum Position
	{
		LEFT("Left"),
		
		RIGHT("Right");
		
		private final String name;
		
		private Position(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	public static enum SortBy
	{
		NAME("Name", (a, b) -> a.getName().compareToIgnoreCase(b.getName())),
		
		WIDTH("Width", Comparator
			.comparingInt(h -> WurstFont.get().width(h.getRenderName())));
		
		private final String name;
		private final Comparator<Hack> comparator;
		
		private SortBy(String name, Comparator<Hack> comparator)
		{
			this.name = name;
			this.comparator = comparator;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
