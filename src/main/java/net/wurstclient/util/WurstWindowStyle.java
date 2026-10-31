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
import java.nio.ByteBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;
import net.wurstclient.other_feature.OtfList;

/**
 * The "Wurst Client" window title and the Wurst window icon, from
 * WurstOptions.
 */
public enum WurstWindowStyle
{
	;
	
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst Window Style");
	private static final Minecraft MC = WurstClient.MC;
	private static final String ICON_PATH = "/assets/wurst/window_icon.png";
	
	private static Boolean applied;
	
	public static boolean isEnabled()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		OtfList otfs = wurst.getOtfs();
		return otfs != null && wurst.isEnabled()
			&& otfs.wurstOptionsOtf.isWindowStyleEnabled();
	}
	
	public static String getTitle()
	{
		return "Wurst Client " + SharedConstants.getCurrentVersion().name();
	}
	
	/**
	 * Updates the window when the setting changes or Wurst is turned on or
	 * off. Called every tick.
	 */
	public static void update()
	{
		boolean enabled = isEnabled();
		if(applied != null && applied == enabled)
			return;
		
		applied = enabled;
		MC.updateTitle();
		
		try
		{
			if(enabled)
				setWurstIcon();
			else
				MC.getWindow().setIcon(MC.getVanillaPackResources(),
					SharedConstants.getCurrentVersion().stable()
						? IconSet.RELEASE : IconSet.SNAPSHOT);
			
		}catch(IOException | RuntimeException e)
		{
			LOGGER.warn("Couldn't change the window icon", e);
		}
	}
	
	/**
	 * Like {@code Window.setIcon()}. Only works on Windows and X11, where the
	 * game can change its icon at all.
	 */
	private static void setWurstIcon() throws IOException
	{
		int platform = GLFW.glfwGetPlatform();
		if(platform != GLFW.GLFW_PLATFORM_WIN32
			&& platform != GLFW.GLFW_PLATFORM_X11)
			return;
		
		try(InputStream in =
			WurstWindowStyle.class.getResourceAsStream(ICON_PATH))
		{
			if(in == null)
				throw new IOException("Missing " + ICON_PATH);
			
			try(NativeImage image = NativeImage.read(in);
				MemoryStack stack = MemoryStack.stackPush())
			{
				ByteBuffer pixels = MemoryUtil
					.memAlloc(image.getWidth() * image.getHeight() * 4);
				try
				{
					pixels.asIntBuffer().put(image.getPixelsABGR());
					GLFWImage.Buffer icons = GLFWImage.malloc(1, stack);
					icons.width(image.getWidth());
					icons.height(image.getHeight());
					icons.pixels(pixels);
					GLFW.glfwSetWindowIcon(MC.getWindow().handle(), icons);
					
				}finally
				{
					MemoryUtil.memFree(pixels);
				}
			}
		}
	}
}
