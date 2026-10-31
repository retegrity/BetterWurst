/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.nametags;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ItemEspHack;
import net.wurstclient.hacks.NameTagsHack;

@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin
{
	@Shadow
	@Final
	public TranslucentFeatureRenderPhase seeThrough;
	
	@WrapOperation(
		method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void wrapLabelScale(PoseStack matrices, float x, float y, float z,
		Operation<Void> original, PoseStack matrices2,
		@Nullable Vec3 nameTagAttachment, int offset, Component name,
		boolean seeThrough, int lightCoords, CameraRenderState camera)
	{
		NameTagsHack nameTagsHack = WurstClient.INSTANCE.getHax().nameTagsHack;
		ItemEspHack itemEspHack = WurstClient.INSTANCE.getHax().itemEspHack;
		
		float userScale;
		if(itemEspHack.isRenderingNameTag())
			userScale = itemEspHack.getNameScale();
		else if(nameTagsHack.isEnabled())
			userScale = nameTagsHack.getScale();
		else
		{
			original.call(matrices, x, y, z);
			return;
		}
		
		float scale = 0.025F * userScale;
		
		if(RenderSystem.getProjectionType() == ProjectionType.PERSPECTIVE)
		{
			double distance = Math.sqrt(TranslucentSubmit
				.computeDistanceToCameraSq(matrices.last().pose()));
			if(distance > 10)
				scale *= distance / 10;
		}
		
		original.call(matrices, scale, -scale, scale);
	}
	
	/**
	 * Makes name tags remain visible while the player is sneaking when NameTags
	 * is enabled.
	 */
	@ModifyVariable(
		method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"),
		argsOnly = true)
	private boolean forceNotSneaking(boolean notSneaking)
	{
		return notSneaking
			|| WurstClient.INSTANCE.getHax().nameTagsHack.isEnabled();
	}
	
	@WrapOperation(
		method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;submitNameTagPart(Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;)V"))
	private void swapNormalNameTagSubmit(SubmitNodeCollection collection,
		TextFeatureRenderer.Submit submit, Operation<Void> original)
	{
		// item names are handled in swapSeeThroughNameTagSubmit()
		if(isRenderingItemName() || !isSeeThrough())
		{
			original.call(collection, submit);
			return;
		}
		
		seeThrough
			.submit(copyWithDisplayMode(submit, Font.DisplayMode.SEE_THROUGH));
	}
	
	@WrapOperation(
		method = "submitNameTag(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;ILnet/minecraft/network/chat/Component;ZILnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/TranslucentSubmit;)V"))
	private void swapSeeThroughNameTagSubmit(
		TranslucentFeatureRenderPhase phase, TranslucentSubmit submit,
		Operation<Void> original)
	{
		// Vanilla draws this layer dimmed and with the background behind
		// blocks, so for item names just make it fully bright.
		if(isRenderingItemName()
			&& submit instanceof TextFeatureRenderer.Submit nameTag && nameTag
				.content() instanceof TextFeatureRenderer.Content.Text text)
		{
			original.call(phase,
				new TextFeatureRenderer.Submit(nameTag.pose(),
					nameTag.displayMode(), nameTag.lightCoords(),
					new TextFeatureRenderer.Content.Text(text.x(), text.y(),
						text.string(), text.dropShadow(), -1,
						text.backgroundColor(), text.outlineColor())));
			return;
		}
		
		if(!isSeeThrough())
		{
			original.call(phase, submit);
			return;
		}
		
		submitNameTagPart(copyWithDisplayMode(
			(TextFeatureRenderer.Submit)submit, Font.DisplayMode.NORMAL));
	}
	
	private boolean isSeeThrough()
	{
		return WurstClient.INSTANCE.getHax().nameTagsHack.isSeeThrough();
	}
	
	private boolean isRenderingItemName()
	{
		return WurstClient.INSTANCE.getHax().itemEspHack.isRenderingNameTag();
	}
	
	private TextFeatureRenderer.Submit copyWithDisplayMode(
		TextFeatureRenderer.Submit nameTag, Font.DisplayMode displayMode)
	{
		return new TextFeatureRenderer.Submit(nameTag.pose(), displayMode,
			nameTag.lightCoords(), nameTag.content());
	}
	
	@Shadow
	private void submitNameTagPart(TextFeatureRenderer.Submit nameTag)
	{
		
	}
}
