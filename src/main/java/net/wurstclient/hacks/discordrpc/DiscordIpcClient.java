/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.discordrpc;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * Minimal implementation of Discord's local IPC protocol, which is all that
 * Rich Presence needs. Works with a Unix domain socket on Linux/macOS and a
 * named pipe on Windows. Not thread-safe, so use it from a single thread.
 */
public final class DiscordIpcClient implements Closeable
{
	private static final int OP_HANDSHAKE = 0;
	private static final int OP_FRAME = 1;
	private static final int OP_CLOSE = 2;
	private static final int OP_PING = 3;
	private static final int OP_PONG = 4;
	
	private final String clientId;
	
	private SocketChannel socket;
	private RandomAccessFile pipe;
	private Thread reader;
	private volatile boolean connected;
	
	public DiscordIpcClient(String clientId)
	{
		this.clientId = clientId;
	}
	
	public boolean isConnected()
	{
		return connected;
	}
	
	/**
	 * Tries to connect to a running Discord client and perform the handshake.
	 *
	 * @throws IOException
	 *             if Discord isn't running or the handshake failed
	 */
	public void connect() throws IOException
	{
		close();
		
		IOException lastError = null;
		for(String path : getCandidatePaths())
			try
			{
				open(path);
				
				JsonObject handshake = new JsonObject();
				handshake.addProperty("v", 1);
				handshake.addProperty("client_id", clientId);
				writeFrame(OP_HANDSHAKE, handshake.toString());
				
				connected = true;
				startReader();
				return;
				
			}catch(IOException e)
			{
				lastError = e;
				closeQuietly();
			}
		
		throw lastError != null ? lastError
			: new IOException("No Discord IPC socket found");
	}
	
	public void setActivity(JsonObject activity) throws IOException
	{
		JsonObject args = new JsonObject();
		args.addProperty("pid", ProcessHandle.current().pid());
		if(activity != null)
			args.add("activity", activity);
		
		JsonObject payload = new JsonObject();
		payload.addProperty("cmd", "SET_ACTIVITY");
		payload.add("args", args);
		payload.addProperty("nonce", UUID.randomUUID().toString());
		
		writeFrame(OP_FRAME, payload.toString());
	}
	
	@Override
	public void close()
	{
		if(connected)
			try
			{
				writeFrame(OP_CLOSE, "{}");
				
			}catch(IOException e)
			{
				// already gone
			}
		
		closeQuietly();
	}
	
	private void closeQuietly()
	{
		connected = false;
		
		try
		{
			if(socket != null)
				socket.close();
			
		}catch(IOException e)
		{}
		
		try
		{
			if(pipe != null)
				pipe.close();
			
		}catch(IOException e)
		{}
		
		socket = null;
		pipe = null;
		reader = null;
	}
	
	private void open(String path) throws IOException
	{
		if(isWindows())
		{
			pipe = new RandomAccessFile(path, "rw");
			return;
		}
		
		if(!Files.exists(Path.of(path)))
			throw new IOException("Socket does not exist: " + path);
		
		socket = SocketChannel.open(StandardProtocolFamily.UNIX);
		socket.connect(UnixDomainSocketAddress.of(path));
	}
	
	private static boolean isWindows()
	{
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
	
	/**
	 * All the places where Discord might have put its socket, including the
	 * Flatpak and Snap sandboxes that are common on Linux.
	 */
	private static List<String> getCandidatePaths()
	{
		List<String> paths = new ArrayList<>();
		
		if(isWindows())
		{
			for(int i = 0; i < 10; i++)
				paths.add("\\\\?\\pipe\\discord-ipc-" + i);
			return paths;
		}
		
		List<String> dirs = new ArrayList<>();
		for(String variable : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP",
			"TEMP"})
		{
			String value = System.getenv(variable);
			if(value != null && !value.isBlank())
				dirs.add(value);
		}
		dirs.add("/tmp");
		
		List<String> subDirs = List.of("", "app/com.discordapp.Discord/",
			"app/com.discordapp.DiscordCanary/", "snap.discord/",
			"snap.discord-canary/");
		
		for(String dir : dirs)
			for(String subDir : subDirs)
				for(int i = 0; i < 10; i++)
				{
					String base = dir.endsWith("/") ? dir : dir + "/";
					paths.add(base + subDir + "discord-ipc-" + i);
				}
			
		return paths.stream().distinct().toList();
	}
	
	private void startReader()
	{
		Thread thread = new Thread(this::readLoop, "DiscordRPC-Reader");
		thread.setDaemon(true);
		reader = thread;
		thread.start();
	}
	
	/**
	 * Discards everything Discord sends us, answers pings and notices when the
	 * connection is closed.
	 */
	private void readLoop()
	{
		try
		{
			while(connected && reader == Thread.currentThread())
			{
				byte[] header = readFully(8);
				ByteBuffer buffer =
					ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
				int opcode = buffer.getInt();
				int length = buffer.getInt();
				if(length < 0 || length > 1 << 20)
					throw new IOException("Invalid frame length: " + length);
				
				byte[] data = readFully(length);
				
				if(opcode == OP_PING)
					writeFrame(OP_PONG,
						new String(data, StandardCharsets.UTF_8));
				else if(opcode == OP_CLOSE)
					break;
			}
			
		}catch(IOException e)
		{
			// connection lost, handled below
		}
		
		if(reader == Thread.currentThread())
			connected = false;
	}
	
	private byte[] readFully(int length) throws IOException
	{
		byte[] data = new byte[length];
		int read = 0;
		while(read < length)
		{
			int n;
			if(socket != null)
				n = socket.read(ByteBuffer.wrap(data, read, length - read));
			else if(pipe != null)
				n = pipe.read(data, read, length - read);
			else
				throw new IOException("Not connected");
			
			if(n < 0)
				throw new IOException("Connection closed");
			read += n;
		}
		return data;
	}
	
	private synchronized void writeFrame(int opcode, String json)
		throws IOException
	{
		byte[] data = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer =
			ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(opcode).putInt(data.length).put(data).flip();
		
		try
		{
			if(socket != null)
				while(buffer.hasRemaining())
					socket.write(buffer);
			else if(pipe != null)
				pipe.write(buffer.array(), 0, buffer.limit());
			else
				throw new IOException("Not connected");
			
		}catch(IOException e)
		{
			connected = false;
			throw e;
		}
	}
}
