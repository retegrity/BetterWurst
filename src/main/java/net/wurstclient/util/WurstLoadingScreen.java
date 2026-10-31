/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;
import net.wurstclient.WurstClient;
import net.wurstclient.other_feature.OtfList;

/**
 * The loading screen from Wurst 1.3 (Minecraft 1.7.2). Back then, Wurst
 * replaced the Mojang logo with a 2560x2560 image, which the game drew as a
 * 256x256 square in the middle of the screen. This draws the same image the
 * same way, except that the TNT blocks on it aren't part of Wurst. They are
 * taken from the current resource pack instead.
 */
public enum WurstLoadingScreen
{
	;
	
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst Loading Screen");
	private static final Minecraft MC = WurstClient.MC;
	
	public static final int BACKGROUND = 0xFF101010;
	
	// The image is the part of the old 2560x2560 one that isn't just
	// background. All positions are in pixels of the old image.
	private static final String IMAGE_PATH = "/assets/wurst/wurst_loading.png";
	private static final int OLD_SIZE = 2560;
	private static final int IMAGE_X = 72;
	private static final int IMAGE_Y = 1091;
	private static final int IMAGE_WIDTH = 2416;
	private static final int IMAGE_HEIGHT = 564;
	
	// {x, y, size} of the TNT blocks
	private static final int[][] TNT =
		{{99, 1203, 155}, {352, 1203, 155}, {2053, 1203, 155},
			{2307, 1203, 155}, {828, 1516, 122}, {1609, 1516, 122}};
	
	// the "loading..." strip, which fills up as the game loads
	private static final int BAR_X1 = 812;
	private static final int BAR_Y1 = 1501;
	private static final int BAR_X2 = 1747;
	private static final int BAR_Y2 = 1654;
	
	private static final Identifier IMAGE_ID =
		Identifier.fromNamespaceAndPath("wurst", "loading_screen");
	private static final Identifier TNT_TEXTURE =
		Identifier.withDefaultNamespace("textures/block/tnt_side.png");
	
	private static NativeImage tnt;
	private static boolean imageOutdated = true;
	private static int imageWidth;
	private static int imageHeight;
	private static boolean imageFailed;
	
	public static boolean isEnabled()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		OtfList otfs = wurst.getOtfs();
		return otfs != null && wurst.isEnabled()
			&& otfs.wurstOptionsOtf.isLoadingScreenEnabled();
	}
	
	/**
	 * Loads the TNT texture of the current resource pack. Called when a new
	 * loading screen opens. The resource packs that are being loaded are
	 * already active at that point, even when the game starts.
	 */
	public static void reloadTnt()
	{
		if(tnt != null)
		{
			tnt.close();
			tnt = null;
		}
		imageOutdated = true;
		
		Optional<Resource> resource =
			MC.getResourceManager().getResource(TNT_TEXTURE);
		if(resource.isEmpty())
			return;
		
		try(InputStream in = resource.get().open();
			NativeImage image = NativeImage.read(in))
		{
			// animated textures are a column of frames, use the first one
			int size = image.getWidth();
			tnt = new NativeImage(size, size, false);
			image.copyRect(tnt, 0, 0, 0, 0, size, size, false, false);
			
		}catch(IOException | RuntimeException e)
		{
			LOGGER.warn("Couldn't load the TNT texture", e);
		}
	}
	
	/**
	 * Fills the background, except where the image goes, since the image
	 * has its own background. Drawing both on top of each other would make
	 * that part fade out slower than the rest of the screen.
	 */
	public static void fillBackground(GuiGraphicsExtractor graphics, int color)
	{
		Layout layout = new Layout(graphics);
		int screenWidth = (graphics.guiWidth() + 1) * layout.guiScale;
		int screenHeight = (graphics.guiHeight() + 1) * layout.guiScale;
		
		graphics.pose().pushMatrix();
		graphics.pose().scale(1F / layout.guiScale, 1F / layout.guiScale);
		
		if(prepareImage(layout.width, layout.height))
		{
			int x1 = layout.x(IMAGE_X);
			int y1 = layout.y(IMAGE_Y);
			int x2 = x1 + layout.width;
			int y2 = y1 + layout.height;
			graphics.fill(0, 0, screenWidth, y1, color);
			graphics.fill(0, y2, screenWidth, screenHeight, color);
			graphics.fill(0, y1, x1, y2, color);
			graphics.fill(x2, y1, screenWidth, y2, color);
			
		}else
			graphics.fill(0, 0, screenWidth, screenHeight, color);
		
		graphics.pose().popMatrix();
	}
	
	/**
	 * Draws the loading screen.
	 *
	 * @param color
	 *            the color that vanilla would draw the logo with, for its
	 *            fade-in and fade-out
	 * @param progress
	 *            how much is loaded, from 0 to 1
	 */
	public static void render(GuiGraphicsExtractor graphics, int color,
		float progress)
	{
		Layout layout = new Layout(graphics);
		if(!prepareImage(layout.width, layout.height))
			return;
		
		graphics.pose().pushMatrix();
		graphics.pose().scale(1F / layout.guiScale, 1F / layout.guiScale);
		
		graphics.blit(RenderPipelines.GUI_TEXTURED, IMAGE_ID, layout.x(IMAGE_X),
			layout.y(IMAGE_Y), 0, 0, layout.width, layout.height, layout.width,
			layout.height, color);
		
		// darken the part of the strip that isn't loaded yet
		int barX1 = layout.x(BAR_X1);
		int barX2 = layout.x(BAR_X2);
		int filledX = barX1 + Math.round((barX2 - barX1) * progress);
		int alpha = Math.round(ARGB.alpha(color) * 0.45F);
		if(filledX < barX2)
			graphics.fill(filledX, layout.y(BAR_Y1), barX2, layout.y(BAR_Y2),
				ARGB.color(alpha, 0, 0, 0));
		
		graphics.pose().popMatrix();
	}
	
	/**
	 * Where things go, in real pixels instead of GUI pixels, so the image
	 * isn't scaled twice. Like in Minecraft 1.7.2, the old image is a
	 * 256x256 GUI pixel square in the middle of the screen.
	 */
	private static final class Layout
	{
		private final int guiScale;
		private final double scale;
		private final int originX;
		private final int originY;
		private final int width;
		private final int height;
		
		private Layout(GuiGraphicsExtractor graphics)
		{
			guiScale = Math.max(1, MC.getWindow().getGuiScale());
			scale = guiScale * 256.0 / OLD_SIZE;
			originX = (graphics.guiWidth() - 256) / 2 * guiScale;
			originY = (graphics.guiHeight() - 256) / 2 * guiScale;
			width = px(IMAGE_WIDTH);
			height = px(IMAGE_HEIGHT);
		}
		
		private int x(int oldImageX)
		{
			return originX + px(oldImageX);
		}
		
		private int y(int oldImageY)
		{
			return originY + px(oldImageY);
		}
		
		private int px(int oldImagePixels)
		{
			return (int)Math.round(oldImagePixels * scale);
		}
	}
	
	/**
	 * Makes a copy of the image, with the TNT painted in, at exactly the
	 * size it's drawn at. Scaling it down smoothly in one go makes the TNT
	 * look like it did in the old image. Drawing the full image that small,
	 * or the TNT texture on its own, would look jagged.
	 *
	 * @return false if the image couldn't be loaded
	 */
	private static boolean prepareImage(int width, int height)
	{
		if(!imageOutdated && width == imageWidth && height == imageHeight)
			return true;
		
		if(imageFailed || width < 1 || height < 1)
			return false;
		
		try(InputStream in =
			WurstLoadingScreen.class.getResourceAsStream(IMAGE_PATH))
		{
			if(in == null)
				throw new IOException("Missing " + IMAGE_PATH);
			
			NativeImage resized = new NativeImage(width, height, false);
			try(NativeImage image = NativeImage.read(in))
			{
				if(tnt != null)
					for(int[] tile : TNT)
						paintTnt(image, tile[0] - IMAGE_X, tile[1] - IMAGE_Y,
							tile[2]);
					
				image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(),
					resized);
			}
			
			TextureManager textures = MC.getTextureManager();
			textures.release(IMAGE_ID);
			textures.register(IMAGE_ID,
				new DynamicTexture(IMAGE_ID::toString, resized));
			imageWidth = width;
			imageHeight = height;
			imageOutdated = false;
			return true;
			
		}catch(IOException | RuntimeException e)
		{
			LOGGER.error("Couldn't load the Wurst loading screen", e);
			imageFailed = true;
			return false;
		}
	}
	
	/**
	 * Paints the TNT texture into the image with sharp pixels, blending
	 * transparent parts with the strip behind it.
	 */
	private static void paintTnt(NativeImage image, int x, int y, int size)
	{
		int tntSize = tnt.getWidth();
		for(int dy = 0; dy < size; dy++)
			for(int dx = 0; dx < size; dx++)
			{
				int src =
					tnt.getPixel(dx * tntSize / size, dy * tntSize / size);
				int dst = image.getPixel(x + dx, y + dy);
				int a = ARGB.alpha(src);
				int r = (ARGB.red(src) * a + ARGB.red(dst) * (255 - a)) / 255;
				int g =
					(ARGB.green(src) * a + ARGB.green(dst) * (255 - a)) / 255;
				int b = (ARGB.blue(src) * a + ARGB.blue(dst) * (255 - a)) / 255;
				image.setPixel(x + dx, y + dy, ARGB.color(255, r, g, b));
			}
	}
}
