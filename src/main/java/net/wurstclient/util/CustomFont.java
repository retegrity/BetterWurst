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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.mojang.blaze3d.platform.TextureUtil;

import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.providers.FreeTypeUtil;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.wurstclient.WurstClient;
import net.wurstclient.mixin.FontAccessor;

/**
 * A {@link Font} that renders text with a TrueType/OpenType font file, using
 * the same FreeType renderer that resource pack fonts use. Characters that the
 * font file doesn't have fall back to the vanilla font.
 */
public final class CustomFont implements AutoCloseable
{
	private static int nextId;
	
	private final TrueTypeGlyphProvider provider;
	private final FontSet fontSet;
	private final IntSet supportedGlyphs;
	private final Font font;
	
	// combined glyph source, cached for the current vanilla glyph source
	private GlyphSource vanillaSource;
	private GlyphSource combinedSource;
	
	private CustomFont(TrueTypeGlyphProvider provider)
	{
		this.provider = provider;
		supportedGlyphs = provider.getSupportedGlyphs();
		
		Identifier texture =
			Identifier.fromNamespaceAndPath("wurst", "font/custom_" + nextId++);
		fontSet = new FontSet(
			new GlyphStitcher(WurstClient.MC.getTextureManager(), texture));
		fontSet.reload(List.of(new GlyphProvider.Conditional(provider,
			FontOption.Filter.ALWAYS_PASS)), Set.of());
		
		font = new Font(new Font.Provider()
		{
			@Override
			public GlyphSource glyphs(FontDescription description)
			{
				GlyphSource vanilla = getVanillaProvider().glyphs(description);
				if(!FontDescription.DEFAULT.equals(description))
					return vanilla;
				
				return getCombinedSource(vanilla);
			}
			
			@Override
			public EffectGlyph effect()
			{
				return getVanillaProvider().effect();
			}
		});
	}
	
	/**
	 * Loads the given font file.
	 *
	 * @param size
	 *            the font size in GUI pixels
	 * @param oversample
	 *            how many real pixels each GUI pixel has, so the glyphs are
	 *            rendered sharply at the current GUI scale
	 * @param shiftY
	 *            moves the glyphs down by this many GUI pixels
	 */
	public static CustomFont load(Path path, float size, float oversample,
		float shiftY) throws IOException
	{
		ByteBuffer buffer;
		try(InputStream in = Files.newInputStream(path))
		{
			// already flipped, ready to read
			buffer = TextureUtil.readResource(in);
		}
		
		FT_Face face = null;
		try
		{
			synchronized(FreeTypeUtil.LIBRARY_LOCK)
			{
				try(MemoryStack stack = MemoryStack.stackPush())
				{
					PointerBuffer pointer = stack.mallocPointer(1);
					FreeTypeUtil
						.assertError(
							FreeType.FT_New_Memory_Face(
								FreeTypeUtil.getLibrary(), buffer, 0, pointer),
							"Initializing font face");
					face = FT_Face.create(pointer.get());
				}
				
				FreeTypeUtil.assertError(FreeType.FT_Select_Charmap(face,
					FreeType.FT_ENCODING_UNICODE), "Find unicode charmap");
			}
			
			return new CustomFont(new TrueTypeGlyphProvider(buffer, face, size,
				oversample, 0, shiftY, ""));
			
		}catch(RuntimeException e)
		{
			if(face != null)
				synchronized(FreeTypeUtil.LIBRARY_LOCK)
				{
					FreeType.FT_Done_Face(face);
				}
			MemoryUtil.memFree(buffer);
			throw new IOException("Couldn't load font " + path, e);
		}
	}
	
	private static Font.Provider getVanillaProvider()
	{
		return ((FontAccessor)WurstClient.MC.font).wurst_getProvider();
	}
	
	private GlyphSource getCombinedSource(GlyphSource vanilla)
	{
		if(vanilla == vanillaSource)
			return combinedSource;
		
		GlyphSource custom = fontSet.source(false);
		vanillaSource = vanilla;
		combinedSource = new GlyphSource()
		{
			@Override
			public BakedGlyph getGlyph(int codepoint)
			{
				return supportedGlyphs.contains(codepoint)
					? custom.getGlyph(codepoint) : vanilla.getGlyph(codepoint);
			}
			
			@Override
			public BakedGlyph getRandomGlyph(RandomSource random, int width)
			{
				return vanilla.getRandomGlyph(random, width);
			}
		};
		return combinedSource;
	}
	
	public Font getFont()
	{
		return font;
	}
	
	@Override
	public void close()
	{
		fontSet.close();
		provider.close();
	}
}
