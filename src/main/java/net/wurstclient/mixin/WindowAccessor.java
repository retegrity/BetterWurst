/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.server.packs.resources.IoSupplier;

@Mixin(Window.class)
public interface WindowAccessor
{
	/**
	 * Sets the window icon from the given images, like vanilla does with its
	 * own icons.
	 */
	@Invoker("setIcon")
	void wurst_setIcon(List<IoSupplier<InputStream>> iconStreams)
		throws IOException;
}
