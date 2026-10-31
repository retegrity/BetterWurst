/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import net.wurstclient.util.WurstTitleScreen;

@Mixin(SplashRenderer.class)
public abstract class SplashRendererMixin
{
	@Shadow
	@Final
	private Component splash;
	
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void onExtractRenderState(GuiGraphicsExtractor graphics, int width,
		Font font, float alpha, CallbackInfo ci)
	{
		WurstTitleScreen.setSplash(splash.getString());
	}
	
	/**
	 * Moves the splash text next to the Wurst logo like in Wurst 6.
	 */
	@ModifyConstant(method = "extractRenderState",
		constant = @Constant(floatValue = 123F))
	private float modifySplashX(float original)
	{
		return WurstTitleScreen.isEnabled() ? 90F : original;
	}
	
	@ModifyConstant(method = "extractRenderState",
		constant = @Constant(floatValue = 69F))
	private float modifySplashY(float original)
	{
		return WurstTitleScreen.isEnabled() ? 72F : original;
	}
	
	/**
	 * Wurst 6 drew the text with its top edge at the pivot, while newer
	 * versions start 8 units higher.
	 */
	@ModifyConstant(method = "extractRenderState",
		constant = @Constant(intValue = -8))
	private int modifySplashTextY(int original)
	{
		return WurstTitleScreen.isEnabled() ? 0 : original;
	}
}
