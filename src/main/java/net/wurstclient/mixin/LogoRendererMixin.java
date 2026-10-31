/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LogoRenderer;
import net.wurstclient.util.WurstTitleScreen;

@Mixin(LogoRenderer.class)
public abstract class LogoRendererMixin
{
	/**
	 * Replaces the Minecraft logo with the Wurst logo.
	 */
	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onExtractRenderState(GuiGraphicsExtractor graphics, int width,
		float alpha, int heightOffset, CallbackInfo ci)
	{
		if(!WurstTitleScreen.isEnabled())
			return;
		
		WurstTitleScreen.renderLogo(graphics, width, alpha, heightOffset);
		ci.cancel();
	}
}
