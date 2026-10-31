/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.time.LocalDate;
import java.time.Month;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.wurstclient.WurstClient;
import net.wurstclient.other_feature.OtfList;

/**
 * The Wurst 6 style main menu: Wurst logo instead of the Minecraft logo,
 * upside-down logo for the "umop-apisdn!" splash, Santa hat in December and
 * the copyright texts in the corners.
 */
public enum WurstTitleScreen
{
	;
	
	public static final String WEBSITE = "www.WurstClient.net";
	public static final String WEBSITE_URL = "https://www.wurstclient.net/";
	
	private static final Identifier LOGO =
		Identifier.fromNamespaceAndPath("wurst", "wurst_380.png");
	private static final Identifier SANTA_HAT =
		Identifier.fromNamespaceAndPath("wurst", "santa_hat.png");
	
	private static final int LOGO_WIDTH = 256;
	private static final int LOGO_HEIGHT = 64;
	private static final int LOGO_TEXTURE_WIDTH = 1500;
	private static final int LOGO_TEXTURE_HEIGHT = 380;
	private static final int HAT_SIZE = 48;
	private static final int HAT_TEXTURE_SIZE = 256;
	
	private static boolean upsideDown;
	
	/**
	 * Whether the Wurst 6 style main menu is turned on in WurstOptions. It's
	 * off while Wurst is disabled.
	 */
	public static boolean isEnabled()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		OtfList otfs = wurst.getOtfs();
		return otfs != null && wurst.isEnabled()
			&& otfs.wurstOptionsOtf.isMainMenuEnabled();
	}
	
	public static void setSplash(String splash)
	{
		upsideDown = "umop-apisdn!".equals(splash);
	}
	
	public static void renderLogo(GuiGraphicsExtractor graphics, int width,
		float alpha, int heightOffset)
	{
		int x = width / 2 - LOGO_WIDTH / 2;
		int y = heightOffset + 6;
		int color = ARGB.white(alpha);
		
		graphics.pose().pushMatrix();
		if(upsideDown)
		{
			graphics.pose().translate(x + LOGO_WIDTH / 2F,
				y + LOGO_HEIGHT / 2F);
			graphics.pose().rotate((float)Math.PI);
			graphics.pose().translate(-(x + LOGO_WIDTH / 2F),
				-(y + LOGO_HEIGHT / 2F));
		}
		
		graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0, 0,
			LOGO_WIDTH, LOGO_HEIGHT, LOGO_TEXTURE_WIDTH, LOGO_TEXTURE_HEIGHT,
			LOGO_TEXTURE_WIDTH, LOGO_TEXTURE_HEIGHT, color);
		
		if(LocalDate.now().getMonth() == Month.DECEMBER)
			graphics.blit(RenderPipelines.GUI_TEXTURED, SANTA_HAT, x + 112,
				y - 36, 0, 0, HAT_SIZE, HAT_SIZE, HAT_TEXTURE_SIZE,
				HAT_TEXTURE_SIZE, HAT_TEXTURE_SIZE, HAT_TEXTURE_SIZE, color);
		
		graphics.pose().popMatrix();
	}
	
	public static void renderTexts(GuiGraphicsExtractor graphics, Font font,
		int width, int height)
	{
		int white = 0xFFFFFFFF;
		
		String[] right =
			{"Minecraft " + SharedConstants.getCurrentVersion().name(),
				"Copyright Mojang AB", "Do not distribute!"};
		for(int i = 0; i < right.length; i++)
			graphics.text(font, right[i], width - font.width(right[i]) - 8,
				8 + i * 10, white);
		
		graphics.text(font, "Wurst Client " + WurstClient.VERSION, 8, 8, white);
		graphics.text(font, "Copyright Alexander01998", 8, 18, white);
		graphics.text(font, "All rights reserved.", 8, 28, white);
		
		graphics.centeredText(font, "§n" + WEBSITE, width / 2, height - 26,
			white);
	}
	
	public static boolean isWebsiteLink(Font font, int width, int height,
		double mouseX, double mouseY)
	{
		int linkWidth = font.width("§n" + WEBSITE);
		return mouseY >= height - 26 && mouseY < height - 16
			&& mouseX > width / 2.0 - linkWidth / 2.0
			&& mouseX < width / 2.0 + linkWidth / 2.0;
	}
}
