/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.settings.FontSetting;
import net.wurstclient.util.WurstColors;

public final class SelectFontScreen extends Screen
{
	private final Screen prevScreen;
	private final FontSetting setting;
	
	private ListGui listGui;
	
	public SelectFontScreen(Screen prevScreen, FontSetting setting)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.setting = setting;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this, setting.listFonts());
		addWidget(listGui);
		
		addRenderableWidget(
			Button.builder(Component.literal("Open Folder"), b -> openFolder())
				.bounds(8, 8, 100, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Refresh"), b -> rebuildWidgets())
				.bounds(width - 108, 8, 100, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Done"), b -> done())
				.bounds(width / 2 - 102, height - 48, 100, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Cancel"), b -> openPrevScreen())
				.bounds(width / 2 + 2, height - 48, 100, 20).build());
	}
	
	private void openFolder()
	{
		Blaze3D.openPath(setting.getFolder());
	}
	
	private void openPrevScreen()
	{
		minecraft.gui.setScreen(prevScreen);
	}
	
	private void done()
	{
		SelectFontScreen.Entry selected = listGui.getSelected();
		if(selected != null)
			setting.setSelected(selected.font);
		
		openPrevScreen();
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == InputConstants.KEY_RETURN)
			done();
		else if(context.key() == InputConstants.KEY_ESCAPE)
			openPrevScreen();
		
		return super.keyPressed(context);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		listGui.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.centeredText(minecraft.font, setting.getName(), width / 2, 12,
			CommonColors.WHITE);
		context.centeredText(minecraft.font,
			"Put your own .ttf/.otf fonts into the fonts folder.", width / 2,
			height - 22, CommonColors.LIGHT_GRAY);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry
		extends ObjectSelectionList.Entry<SelectFontScreen.Entry>
	{
		// null for the Minecraft font
		private final Path font;
		
		public Entry(Path font)
		{
			this.font = font;
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select",
				"Font " + FontSetting.getFontName(font));
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			Font tr = minecraft.font;
			
			String name = FontSetting.getFontName(font);
			context.text(tr, name, x + 28, y, WurstColors.VERY_LIGHT_GRAY);
			
			String source;
			if(font == null)
				source = "Default";
			else if(setting.isInFontsFolder(font))
				source = "Wurst fonts folder";
			else
				source = "" + font;
			context.text(tr, source, x + 28, y + 9, CommonColors.LIGHT_GRAY);
		}
	}
	
	private final class ListGui
		extends ObjectSelectionList<SelectFontScreen.Entry>
	{
		public ListGui(Minecraft mc, SelectFontScreen screen, List<Path> fonts)
		{
			super(mc, screen.width, screen.height - 96, 36, 20);
			
			ArrayList<Path> all = new ArrayList<>();
			all.add(null);
			all.addAll(fonts);
			
			for(Path font : all)
			{
				SelectFontScreen.Entry entry = new SelectFontScreen.Entry(font);
				addEntry(entry);
				if(Objects.equals(font, setting.getSelected()))
					setSelected(entry);
			}
		}
	}
}
