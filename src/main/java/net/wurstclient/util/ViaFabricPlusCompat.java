/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.lang.reflect.Method;

/**
 * Detects the server version that ViaFabricPlus is translating to, without
 * depending on ViaFabricPlus. Everything is looked up with reflection, so this
 * simply returns false when ViaFabricPlus isn't installed.
 */
public enum ViaFabricPlusCompat
{
	;
	
	// null for the static ViaFabricPlus 3.x method
	private static final Method getImpl;
	private static final Method getTargetVersion;
	private static final Object v1_8;
	private static final Method olderThanOrEqualTo;
	
	static
	{
		Method impl = null;
		Method getter = null;
		Object version = null;
		Method compare = null;
		
		try
		{
			ClassLoader loader = ViaFabricPlusCompat.class.getClassLoader();
			Class<?> protocolVersion = Class.forName(
				"com.viaversion.viaversion.api.protocol.version.ProtocolVersion",
				false, loader);
			version = protocolVersion.getField("v1_8").get(null);
			compare = protocolVersion.getMethod("olderThanOrEqualTo",
				protocolVersion);
			
			try
			{
				// ViaFabricPlus 4.x and 5.x API
				Class<?> vfp =
					Class.forName("com.viaversion.viafabricplus.ViaFabricPlus",
						false, loader);
				impl = vfp.getMethod("getImpl");
				getter = impl.getReturnType().getMethod("getTargetVersion");
				
			}catch(ReflectiveOperationException e)
			{
				// ViaFabricPlus 3.x
				Class<?> translator = Class.forName(
					"de.florianmichael.viafabricplus.protocoltranslator.ProtocolTranslator",
					false, loader);
				impl = null;
				getter = translator.getMethod("getTargetVersion");
			}
			
		}catch(ReflectiveOperationException | LinkageError e)
		{
			// ViaFabricPlus isn't installed
			impl = null;
			getter = null;
		}
		
		getImpl = impl;
		getTargetVersion = getter;
		v1_8 = version;
		olderThanOrEqualTo = compare;
	}
	
	/**
	 * Returns true if ViaFabricPlus is installed and set to Minecraft 1.8 or
	 * older.
	 */
	public static boolean isOneEightOrOlder()
	{
		if(getTargetVersion == null)
			return false;
		
		try
		{
			Object holder = null;
			if(getImpl != null)
			{
				holder = getImpl.invoke(null);
				if(holder == null)
					return false;
			}
			
			Object target = getTargetVersion.invoke(holder);
			return target != null
				&& (boolean)olderThanOrEqualTo.invoke(target, v1_8);
			
		}catch(ReflectiveOperationException | RuntimeException e)
		{
			return false;
		}
	}
}
