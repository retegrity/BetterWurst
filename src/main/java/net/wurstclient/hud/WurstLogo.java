/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.HackListOtf;
import net.wurstclient.other_features.WurstLogoOtf;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstFont;

public final class WurstLogo
{
	private static final WurstClient WURST = WurstClient.INSTANCE;
	private static final Identifier LOGO_TEXTURE =
		Identifier.fromNamespaceAndPath("wurst", "wurst_128.png");
	
	public void render(GuiGraphicsExtractor context)
	{
		WurstLogoOtf otf = WURST.getOtfs().wurstLogoOtf;
		if(!otf.isVisible())
			return;
		
		if(otf.isTextLogo())
		{
			renderTextLogo(context, otf);
			return;
		}
		
		if(!otf.isOnlyLogo())
		{
			String version = getVersionString();
			Font tr = WurstFont.get();
			
			// background
			int bgColor;
			if(WURST.getHax().rainbowUiHack.isEnabled())
				bgColor =
					RenderUtils.toIntColor(WURST.getGui().getAcColor(), 0.5F);
			else
				bgColor = otf.getBackgroundColor();
			context.fill(0, 6, tr.width(version) + 76, 17, bgColor);
			
			context.guiRenderState.up();
			
			// version string
			context.text(tr, version, 74, 8, otf.getTextColor(), false);
		}
		
		// Wurst logo
		context.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, 0, 3, 0, 0, 72,
			18, 72, 18);
	}
	
	/**
	 * Renders the logo as the text "Wurst" (optionally with the version),
	 * scaled up, instead of the image and the version strip.
	 */
	private void renderTextLogo(GuiGraphicsExtractor context, WurstLogoOtf otf)
	{
		String text = "Wurst";
		if(otf.isTextVersion())
			text += " " + WurstClient.VERSION;
		
		int color = getTextLogoColor(otf);
		float scale = otf.getTextSize();
		
		// the custom font is loaded at the scaled-up resolution, so it stays
		// sharp (the Minecraft font is pixel art, so scaling it is fine)
		Font tr = otf.isTextCustomFont()
			? WURST.getOtfs().fontOtf.getFont(scale) : WurstClient.MC.font;
		
		Matrix3x2fStack matrices = context.pose();
		matrices.pushMatrix();
		matrices.translate(2, 2);
		matrices.scale(scale, scale);
		context.text(tr, text, 0, 0, color, true);
		matrices.popMatrix();
	}
	
	private int getTextLogoColor(WurstLogoOtf otf)
	{
		HackListOtf hackList = WURST.getOtfs().hackListOtf;
		boolean sync = otf.isTextSyncedWithHackList();
		
		// one entry above the HackList's first entry, so the rainbow continues
		// from the logo down the list
		if(sync && hackList.isRainbow())
			return 0xFF000000
				| hackList.getRainbowColor(System.nanoTime() / 1e9, -1);
		
		if(WURST.getHax().rainbowUiHack.isEnabled())
			return RenderUtils.toIntColor(WURST.getGui().getAcColor(), 1);
		
		if(sync)
			return hackList.getColor(0xFF);
		
		return otf.getTextLogoColor();
	}
	
	private String getVersionString()
	{
		String version = "v" + WurstClient.VERSION;
		version += " MC" + WurstClient.MC_VERSION;
		
		if(WURST.getUpdater().isOutdated())
			version += " (outdated)";
		
		return version;
	}
}
