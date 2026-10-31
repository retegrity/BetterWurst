/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"smooth hand bob", "HandBobbing", "hand bobbing", "NoHandBob",
	"no hand bob", "NoBob", "no bob", "SmoothBob", "smooth bob"})
public final class SmoothHandBobHack extends Hack
	implements CameraTransformViewBobbingListener
{
	private final SliderSetting height = new SliderSetting("Height",
		"How far your hand goes down while you are moving, compared to the"
			+ " lowest point of the normal bobbing animation.",
		1, 0, 2, 0.05, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting noWorldBob =
		new CheckboxSetting("Remove world bobbing",
			"Stops the world from bobbing while you walk, so only your hand"
				+ " moves.",
			true);
	
	private final CheckboxSetting noConsumingBob = new CheckboxSetting(
		"Also include consuming animation",
		"Stops food and drinks from bobbing up and down while you eat or"
			+ " drink. They still move up to your mouth, then stay in place.",
		false);
	
	public SmoothHandBobHack()
	{
		super("SmoothHandBob");
		setCategory(Category.RENDER);
		addSetting(height);
		addSetting(noWorldBob);
		addSetting(noConsumingBob);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(noWorldBob.isChecked())
			event.cancel();
	}
	
	/**
	 * Replaces the hand's bobbing with a smooth drop. Vanilla's bob amount
	 * already rises while you start moving and falls off when you stop, so
	 * moving the hand down by it (without the side-to-side swaying and
	 * rotations) gives the old-client look.
	 *
	 * Called from GameRendererMixin instead of vanilla's bobView() for the
	 * hand. Only works with the View Bobbing option enabled, like vanilla.
	 */
	/**
	 * Whether the bobbing of food and drinks while eating or drinking should
	 * be removed. Called from smoothhandbob.ItemInHandRendererMixin.
	 */
	public boolean isConsumingBobRemoved()
	{
		return isEnabled() && noConsumingBob.isChecked();
	}
	
	public void bobHand(CameraRenderState cameraState, PoseStack matrices)
	{
		if(!cameraState.entityRenderState.isPlayer)
			return;
		
		float bob = cameraState.entityRenderState.bob;
		matrices.translate(0, -bob * height.getValueF(), 0);
	}
}
