/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"wurst options", "settings"})
@DontBlock
public final class WurstOptionsOtf extends OtherFeature
{
	private static final Identifier WURST_TEXTURE =
		Identifier.fromNamespaceAndPath("wurst", "wurst_128.png");
	
	private final EnumSetting<Location> location = new EnumSetting<>("Location",
		"description.wurst.setting.wurstoptions.location", Location.values(),
		Location.GAME_MENU);
	
	private final SliderSetting lineWidth = new SliderSetting("Line width",
		"How thick the lines drawn by ESP, tracers, Navigator and other"
			+ " hacks are, in pixels.\n\n"
			+ "Lower this if lines look too thick compared to older versions.",
		2, 0.5, 4, 0.25, ValueDisplay.DECIMAL.withSuffix(" px"));
	
	private final CheckboxSetting mainMenu =
		new CheckboxSetting("Wurst main menu",
			"Brings back the custom Wurst main menu from Wurst v6.", true);
	
	private final CheckboxSetting windowStyle =
		new CheckboxSetting("Wurst Minecraft window style",
			"Changes the title of the game window to \"Wurst Client\" and the"
				+ " Minecraft version, and its icon to the Wurst icon.",
			true);
	
	private final CheckboxSetting loadingScreen =
		new CheckboxSetting("Wurst loading screen",
			"Replaces the Mojang loading screen with the one from Wurst 1.3."
				+ " The TNT blocks on it come from your current resource pack.",
			true);
	
	public WurstOptionsOtf()
	{
		super("WurstOptions", "description.wurst.other_feature.wurstoptions");
		addSetting(location);
		addSetting(lineWidth);
		addSetting(mainMenu);
		addSetting(windowStyle);
		addSetting(loadingScreen);
	}
	
	public boolean isMainMenuEnabled()
	{
		return mainMenu.isChecked();
	}
	
	public boolean isWindowStyleEnabled()
	{
		return windowStyle.isChecked();
	}
	
	public boolean isLoadingScreenEnabled()
	{
		return loadingScreen.isChecked();
	}
	
	public float getLineWidth()
	{
		return lineWidth.getValueF();
	}
	
	public boolean isVisibleInGameMenu()
	{
		return WURST.isEnabled()
			&& location.getSelected() == Location.GAME_MENU;
	}
	
	public boolean isVisibleInStatistics()
	{
		return WURST.isEnabled()
			&& location.getSelected() == Location.STATISTICS;
	}
	
	public Button.Builder buttonBuilder(OnPress onPress)
	{
		MutableComponent message = Component.literal("            Options");
		
		MutableComponent narration =
			Component.translatable("gui.narrate.button", "Wurst Options");
		
		Tooltip tooltip = Tooltip.create(Component.literal(getDescription()));
		
		return Button.builder(message, onPress)
			.createNarration(sup -> narration).tooltip(tooltip);
	}
	
	public void drawWurstLogoOnButton(GuiGraphicsExtractor context,
		Button wurstOptionsButton)
	{
		if(wurstOptionsButton == null)
			return;
		
		int x = wurstOptionsButton.getX() + 34;
		int y = wurstOptionsButton.getY() + 2;
		int w = 63;
		int h = 16;
		int fw = 63;
		int fh = 16;
		float u = 0;
		float v = 0;
		context.guiRenderState.up();
		context.blit(RenderPipelines.GUI_TEXTURED, WURST_TEXTURE, x, y, u, v, w,
			h, fw, fh);
	}
	
	private enum Location
	{
		GAME_MENU("Game Menu"),
		STATISTICS("Statistics");
		
		private final String name;
		
		private Location(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
