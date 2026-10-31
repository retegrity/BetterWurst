/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.WurstFont;

public final class ComboBoxPopup<T extends Enum<T>> extends Popup
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	
	private final EnumSetting<T> setting;
	private final int popupWidth;
	private int scroll;
	
	public ComboBoxPopup(Component owner, EnumSetting<T> setting,
		int popupWidth)
	{
		super(owner);
		this.setting = setting;
		this.popupWidth = popupWidth;
		
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
		
		setX(owner.getWidth() - getWidth());
		setY(owner.getHeight());
		limitHeightToScreen(owner);
	}
	
	@Override
	public void handleMouseClick(int mouseX, int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(mouseY < getY() || mouseY >= getY() + getHeight())
			return;
		
		int yi1 = getY() + scroll - 11;
		for(T value : setting.getValues())
		{
			if(value == setting.getSelected())
				continue;
			
			yi1 += 11;
			int yi2 = yi1 + 11;
			
			if(mouseY < yi1 || mouseY >= yi2)
				continue;
			
			setting.setSelected(value);
			close();
			break;
		}
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY, x1, x2, y1, y2);
		
		if(hovering)
			GUI.setTooltip("");
		
		context.enableScissor(x1, y1, x2, y2);
		
		int yi1 = y1 + scroll - 11;
		for(T value : setting.getValues())
		{
			if(value == setting.getSelected())
				continue;
			
			yi1 += 11;
			int yi2 = yi1 + 11;
			
			// skip entries that are scrolled out of view
			if(yi2 < y1 || yi1 > y2)
				continue;
			
			boolean hValue = hovering && mouseY >= yi1 && mouseY < yi2;
			context.fill(x1, yi1, x2, yi2, RenderUtils.toIntColor(
				GUI.getBgColor(), GUI.getOpacity() * (hValue ? 1.5F : 1)));
			
			context.guiRenderState.up();
			context.text(WurstFont.get(), value.toString(), x1 + 2, yi1 + 2,
				GUI.getTxtColor(), false);
		}
		
		context.disableScissor();
		
		// scroll indicator
		int maxScroll = getMaxScroll();
		if(maxScroll > 0)
		{
			int barHeight = Math.max(8, (int)((long)getHeight() * getHeight()
				/ (getHeight() + maxScroll)));
			int barY = y1
				+ (int)((long)(getHeight() - barHeight) * -scroll / maxScroll);
			context.fill(x2 - 3, barY, x2 - 1, barY + barHeight,
				RenderUtils.toIntColor(GUI.getAcColor(), 0.75F));
		}
		
		// The border goes on top, so the entries can't cover its right and
		// bottom edges when the opacity is high.
		context.guiRenderState.up();
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
			RenderUtils.toIntColor(GUI.getAcColor(), 0.5F));
	}
	
	@Override
	public void handleMouseScroll(double delta)
	{
		int maxScroll = getMaxScroll();
		if(maxScroll <= 0)
			return;
		
		scroll += (int)Math.round(delta * 33);
		scroll = Math.max(-maxScroll, Math.min(0, scroll));
	}
	
	private int getMaxScroll()
	{
		return Math.max(0, getDefaultHeight() - getHeight());
	}
	
	/**
	 * Shrinks the popup if it would run off the bottom of the screen. The
	 * entries can then be scrolled with the mouse wheel.
	 */
	private void limitHeightToScreen(Component owner)
	{
		Window parent = owner.getParent();
		if(parent == null)
			return;
		
		int top = parent.getY() + 13 + parent.getScrollOffset() + owner.getY()
			+ getY();
		int available =
			WurstClient.MC.getWindow().getGuiScaledHeight() - top - 4;
		
		// always show at least a few entries, even in tiny windows
		int minHeight = Math.min(getDefaultHeight(), 11 * 5);
		setHeight(Math.min(getDefaultHeight(), Math.max(minHeight, available)));
	}
	
	private boolean isHovering(int mouseX, int mouseY, int x1, int x2, int y1,
		int y2)
	{
		return mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2;
	}
	
	@Override
	public int getDefaultWidth()
	{
		return popupWidth + 15;
	}
	
	@Override
	public int getDefaultHeight()
	{
		int numValues = setting.getValues().length;
		return (numValues - 1) * 11;
	}
}
