/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.wurstclient.WurstClient;

/**
 * Sends several packets in one go, so that they reach the server together and
 * are handled between the same two server ticks. See ConnectionMixin.
 */
public enum PacketBatcher
{
	;
	
	private static final Minecraft MC = WurstClient.MC;
	private static boolean batching;
	
	public static void run(Runnable sendPackets)
	{
		batching = true;
		try
		{
			sendPackets.run();
			
		}finally
		{
			batching = false;
			ClientPacketListener connection = MC.getConnection();
			if(connection != null)
				connection.getConnection().flushChannel();
		}
	}
	
	public static boolean isBatching()
	{
		return batching && MC.isSameThread();
	}
}
