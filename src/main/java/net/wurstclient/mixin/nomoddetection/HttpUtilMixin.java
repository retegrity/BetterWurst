/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.nomoddetection;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.util.HttpUtil;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.NoModDetectionOtf;

/**
 * NoModDetection's "Block local pack URLs". Follows redirects manually, so
 * that every URL along the way can be checked.
 */
@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin
{
	@Unique
	private static final String DOWNLOAD_FILE =
		"downloadFile(Ljava/nio/file/Path;Ljava/net/URL;Ljava/util/Map;Lcom/google/common/hash/HashFunction;Lcom/google/common/hash/HashCode;ILjava/net/Proxy;Lnet/minecraft/util/HttpUtil$DownloadProgressListener;)Ljava/nio/file/Path;";
	
	@WrapOperation(method = DOWNLOAD_FILE,
		at = @At(value = "INVOKE",
			target = "Ljava/net/URL;openConnection(Ljava/net/Proxy;)Ljava/net/URLConnection;",
			remap = false))
	private static URLConnection wrapOpenConnection(URL url, Proxy proxy,
		Operation<URLConnection> original) throws IOException
	{
		getNoModDetection().checkPackHost(url.getHost());
		return original.call(url, proxy);
	}
	
	@WrapOperation(method = DOWNLOAD_FILE,
		at = @At(value = "INVOKE",
			target = "Ljava/net/HttpURLConnection;setInstanceFollowRedirects(Z)V",
			remap = false))
	private static void wrapSetInstanceFollowRedirects(
		HttpURLConnection connection, boolean followRedirects,
		Operation<Void> original)
	{
		original.call(connection,
			followRedirects && !getNoModDetection().isBlockingLocalUrls());
	}
	
	@WrapOperation(method = DOWNLOAD_FILE,
		at = @At(value = "INVOKE",
			target = "Ljava/net/HttpURLConnection;getInputStream()Ljava/io/InputStream;",
			remap = false))
	private static InputStream wrapGetInputStream(HttpURLConnection connection,
		Operation<InputStream> original,
		@Local LocalRef<HttpURLConnection> connectionRef,
		@Local(argsOnly = true) Map<String, String> headers,
		@Local(argsOnly = true) Proxy proxy) throws IOException
	{
		NoModDetectionOtf noModDetection = getNoModDetection();
		if(!noModDetection.isBlockingLocalUrls())
			return original.call(connection);
		
		// like HttpURLConnection's own redirects: same protocol only
		for(int redirects = 0;; redirects++)
		{
			int status = connection.getResponseCode();
			String location = connection.getHeaderField("Location");
			if(location == null || status < 300 || status > 399
				|| status == 304)
				break;
			
			if(redirects >= noModDetection.getMaxRedirects())
				throw new IOException("Too many redirects");
			
			URL url = new URL(connection.getURL(), location);
			if(!url.getProtocol()
				.equalsIgnoreCase(connection.getURL().getProtocol()))
				break;
			
			noModDetection.checkPackHost(url.getHost());
			connection.disconnect();
			
			connection = (HttpURLConnection)url.openConnection(proxy);
			connection.setInstanceFollowRedirects(false);
			headers.forEach(connection::setRequestProperty);
			connectionRef.set(connection);
		}
		
		return original.call(connection);
	}
	
	@Unique
	private static NoModDetectionOtf getNoModDetection()
	{
		return WurstClient.INSTANCE.getOtfs().noModDetectionOtf;
	}
}
