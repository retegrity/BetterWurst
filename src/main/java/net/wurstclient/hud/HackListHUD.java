/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_features.HackListOtf;
import net.wurstclient.other_features.HackListOtf.Mode;
import net.wurstclient.other_features.HackListOtf.Position;
import net.wurstclient.util.WurstFont;

public final class HackListHUD implements UpdateListener
{
	private final ArrayList<HackListEntry> activeHax = new ArrayList<>();
	private final HackListOtf otf = WurstClient.INSTANCE.getOtfs().hackListOtf;
	private int posY;
	private int textColor;
	
	public HackListHUD()
	{
		WurstClient.INSTANCE.getEventManager().add(UpdateListener.class, this);
	}
	
	public void render(GuiGraphicsExtractor context, float partialTicks)
	{
		if(otf.getMode() == Mode.HIDDEN)
			return;
		
		if(otf.getPosition() == Position.LEFT
			&& WurstClient.INSTANCE.getOtfs().wurstLogoOtf.isVisible())
			posY = WurstClient.INSTANCE.getOtfs().wurstLogoOtf.getBottom() + 1;
		else
			posY = 2;
		posY += otf.getVerticalOffset();
		
		// color
		if(WurstClient.INSTANCE.getHax().rainbowUiHack.isEnabled())
		{
			float[] acColor = WurstClient.INSTANCE.getGui().getAcColor();
			textColor = 0x04 << 24 | (int)(acColor[0] * 0xFF) << 16
				| (int)(acColor[1] * 0xFF) << 8 | (int)(acColor[2] * 0xFF);
			
		}else
			textColor = otf.getColor(0x04);
		
		int height = posY + activeHax.size() * 9;
		
		ArrayList<Line> lines = new ArrayList<>();
		if(otf.getMode() == Mode.COUNT || height > context.guiHeight())
			lines.add(getCounterLine());
		else if(otf.isAnimations())
			for(HackListEntry e : activeHax)
				lines.add(getLineWithOffset(context, e, partialTicks));
		else
			for(HackListEntry e : activeHax)
				lines.add(getLine(context, e.hack.getRenderName(), 0, 1));
			
		drawLines(context, lines);
	}
	
	private Line getCounterLine()
	{
		long size = activeHax.stream().filter(e -> e.hack.isEnabled()).count();
		String s = size + " hack" + (size != 1 ? "s" : "") + " active";
		return new Line(s, 0, 1);
	}
	
	private Line getLine(GuiGraphicsExtractor context, String s, float offset,
		float opacity)
	{
		return new Line(s, offset, opacity);
	}
	
	private Line getLineWithOffset(GuiGraphicsExtractor context,
		HackListEntry e, float partialTicks)
	{
		float offset =
			e.offset * partialTicks + e.prevOffset * (1 - partialTicks);
		return new Line(e.hack.getRenderName(), offset, 1 - offset / 4);
	}
	
	/**
	 * Draws the background and outline (like Meteor's active modules list)
	 * first, then the text on top.
	 */
	private void drawLines(GuiGraphicsExtractor context, List<Line> lines)
	{
		Font tr = WurstFont.get();
		boolean left = otf.getPosition() == Position.LEFT;
		boolean rainbow = otf.isRainbow();
		double seconds = System.nanoTime() / 1e9;
		int screenWidth = context.guiWidth();
		int offsetX = otf.getHorizontalOffset();
		
		int size = lines.size();
		int[] x1 = new int[size];
		int[] x2 = new int[size];
		int[] y = new int[size];
		int[] colors = new int[size];
		
		for(int i = 0; i < size; i++)
		{
			Line line = lines.get(i);
			int width = tr.width(line.text());
			int posX = left ? (int)(2 + offsetX - 5 * line.offset())
				: (int)(screenWidth - width - 2 - offsetX + 5 * line.offset());
			
			x1[i] = posX - 1;
			x2[i] = posX + width + 1;
			y[i] = posY + i * 9 - 1;
			colors[i] = rainbow ? otf.getRainbowColor(seconds, i)
				: textColor & 0xFFFFFF;
		}
		
		// background
		if(otf.hasBackground())
		{
			int bg = otf.getBackgroundColor();
			for(int i = 0; i < size; i++)
			{
				int alpha = (int)((bg >>> 24) * lines.get(i).opacity());
				context.fill(x1[i], y[i], x2[i], y[i] + 9,
					alpha << 24 | bg & 0xFFFFFF);
			}
		}
		
		// outline: sides of every entry, plus the top, the bottom and the
		// steps between entries of different widths
		if(otf.hasOutline() && size > 0)
		{
			int w = otf.getOutlineWidth();
			for(int i = 0; i < size; i++)
			{
				int color = outlineColor(rainbow, colors[i], lines.get(i));
				int top = y[i];
				int bottom = y[i] + 9;
				context.fill(x1[i] - w, top, x1[i], bottom, color);
				context.fill(x2[i], top, x2[i] + w, bottom, color);
				
				if(i == 0)
					context.fill(x1[i] - w, top - w, x2[i] + w, top, color);
				
				if(i == size - 1)
				{
					context.fill(x1[i] - w, bottom, x2[i] + w, bottom + w,
						color);
					continue;
				}
				
				// steps to the next entry
				int nx1 = x1[i + 1];
				int nx2 = x2[i + 1];
				if(nx1 != x1[i])
					context.fill(Math.min(x1[i], nx1) - w, bottom,
						Math.max(x1[i], nx1), bottom + w, color);
				if(nx2 != x2[i])
					context.fill(Math.min(x2[i], nx2), bottom,
						Math.max(x2[i], nx2) + w, bottom + w, color);
			}
		}
		
		context.guiRenderState.up();
		
		// text
		for(int i = 0; i < size; i++)
		{
			Line line = lines.get(i);
			int alpha = (int)(255 * line.opacity()) << 24;
			int posX = x1[i] + 1;
			context.text(tr, line.text(), posX + 1, y[i] + 2,
				0x04000000 | alpha, false);
			context.guiRenderState.up();
			context.text(tr, line.text(), posX, y[i] + 1, colors[i] | alpha,
				false);
		}
	}
	
	private int outlineColor(boolean rainbow, int lineColor, Line line)
	{
		int alpha = (int)(255 * line.opacity());
		int rgb = rainbow ? lineColor : otf.getOutlineColor() & 0xFFFFFF;
		return alpha << 24 | rgb;
	}
	
	public void updateState(Hack hack)
	{
		int offset = otf.isAnimations() ? 4 : 0;
		HackListEntry entry = new HackListEntry(hack, offset);
		
		if(hack.isEnabled())
		{
			if(activeHax.contains(entry))
				return;
			
			activeHax.add(entry);
			sort();
			
		}else if(!otf.isAnimations())
			activeHax.remove(entry);
	}
	
	private void sort()
	{
		Comparator<HackListEntry> comparator =
			Comparator.comparing(hle -> hle.hack, otf.getComparator());
		Collections.sort(activeHax, comparator);
	}
	
	@Override
	public void onUpdate()
	{
		if(otf.shouldSort())
			sort();
		
		if(!otf.isAnimations())
			return;
		
		for(Iterator<HackListEntry> itr = activeHax.iterator(); itr.hasNext();)
		{
			HackListEntry e = itr.next();
			boolean enabled = e.hack.isEnabled();
			e.prevOffset = e.offset;
			
			if(enabled && e.offset > 0)
				e.offset--;
			else if(!enabled && e.offset < 4)
				e.offset++;
			else if(!enabled && e.offset >= 4)
				itr.remove();
		}
	}
	
	private record Line(String text, float offset, float opacity)
	{}
	
	private static final class HackListEntry
	{
		private final Hack hack;
		private int offset;
		private int prevOffset;
		
		public HackListEntry(Hack mod, int offset)
		{
			hack = mod;
			this.offset = offset;
			prevOffset = offset;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if(!(obj instanceof HackListEntry other))
				return false;
			
			return hack == other.hack;
		}
		
		@Override
		public int hashCode()
		{
			return hack.hashCode();
		}
	}
}
