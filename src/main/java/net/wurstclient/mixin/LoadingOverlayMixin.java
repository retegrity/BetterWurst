/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import net.wurstclient.util.WurstLoadingScreen;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin
{
	@Shadow
	private float currentProgress;
	
	/**
	 * Loads the TNT texture of the resource packs that this loading screen
	 * is loading.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void onInit(CallbackInfo ci)
	{
		if(WurstLoadingScreen.isEnabled())
			WurstLoadingScreen.reloadTnt();
	}
	
	/**
	 * Uses the dark background of the Wurst loading screen instead of the
	 * Mojang red (or black).
	 */
	@ModifyExpressionValue(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Ljava/util/function/IntSupplier;getAsInt()I"))
	private int onGetBackground(int original)
	{
		return WurstLoadingScreen.isEnabled() ? WurstLoadingScreen.BACKGROUND
			: original;
	}
	
	/**
	 * Leaves out the part of the background that the Wurst loading screen
	 * covers, so the whole screen fades in and out evenly.
	 */
	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void onFillBackground(GuiGraphicsExtractor graphics, int x1, int y1,
		int x2, int y2, int color, Operation<Void> original)
	{
		if(WurstLoadingScreen.isEnabled())
			WurstLoadingScreen.fillBackground(graphics, color);
		else
			original.call(graphics, x1, y1, x2, y2, color);
	}
	
	/**
	 * Draws the Wurst loading screen instead of the left half of the Mojang
	 * logo.
	 */
	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
			ordinal = 0))
	private void onBlitLogoLeft(GuiGraphicsExtractor graphics,
		RenderPipeline pipeline, Identifier texture, int x, int y, float u,
		float v, int width, int height, int srcWidth, int srcHeight,
		int textureWidth, int textureHeight, int color,
		Operation<Void> original)
	{
		if(!WurstLoadingScreen.isEnabled())
		{
			original.call(graphics, pipeline, texture, x, y, u, v, width,
				height, srcWidth, srcHeight, textureWidth, textureHeight,
				color);
			return;
		}
		
		WurstLoadingScreen.render(graphics, color, currentProgress);
	}
	
	/**
	 * Skips the right half of the Mojang logo.
	 */
	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V",
			ordinal = 1))
	private void onBlitLogoRight(GuiGraphicsExtractor graphics,
		RenderPipeline pipeline, Identifier texture, int x, int y, float u,
		float v, int width, int height, int srcWidth, int srcHeight,
		int textureWidth, int textureHeight, int color,
		Operation<Void> original)
	{
		if(!WurstLoadingScreen.isEnabled())
			original.call(graphics, pipeline, texture, x, y, u, v, width,
				height, srcWidth, srcHeight, textureWidth, textureHeight,
				color);
	}
	
	/**
	 * Skips the progress bar, since the "loading..." strip fills up instead.
	 */
	@WrapOperation(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;extractProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"))
	private void onExtractProgressBar(LoadingOverlay overlay,
		GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
		float fade, Operation<Void> original)
	{
		if(!WurstLoadingScreen.isEnabled())
			original.call(overlay, graphics, x0, y0, x1, y1, fade);
	}
}
