/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.MathUtils;

public final class VClipCmd extends Command
{
	/**
	 * How far the server is expected to accept a single movement packet.
	 * Every extra packet sent before the real one raises the distance the
	 * server will tolerate by about this much.
	 */
	private static final int BLOCKS_PER_PACKET = 10;
	private static final int MAX_PACKETS = 20;
	private static final int MAX_DISTANCE = BLOCKS_PER_PACKET * MAX_PACKETS;
	private static final int SCAN_RANGE = 128;
	
	public VClipCmd()
	{
		super("vclip", "Lets you clip through blocks vertically.\n"
			+ "Sends extra movement packets so you can go further than\n"
			+ "10 blocks. The maximum distance is " + MAX_DISTANCE + " blocks.",
			".vclip <height>", ".vclip (up|down)");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length != 1)
			throw new CmdSyntaxError();
		
		if(MathUtils.isDouble(args[0]))
		{
			double height = Double.parseDouble(args[0]);
			if(Math.abs(height) > MAX_DISTANCE)
				throw new CmdError(
					"The maximum distance is " + MAX_DISTANCE + " blocks.");
			
			vclip(height);
			return;
		}
		
		switch(args[0].toLowerCase())
		{
			case "up":
			vclip(calculateHeight(Direction.UP));
			break;
			
			case "down":
			vclip(calculateHeight(Direction.DOWN));
			break;
			
			default:
			throw new CmdSyntaxError();
		}
	}
	
	private double calculateHeight(Direction direction) throws CmdError
	{
		AABB box = MC.player.getBoundingBox();
		
		AABB maxOffsetBox = box.move(0, direction.getStepY() * SCAN_RANGE, 0);
		if(!hasCollisions(box.minmax(maxOffsetBox)))
			throw new CmdError("There is nothing to clip through!");
		
		for(int i = 1; i <= SCAN_RANGE; i++)
		{
			double height = direction.getStepY() * i;
			AABB offsetBox = box.move(0, height, 0);
			if(!MC.level.isLoaded(BlockPos.containing(offsetBox.getCenter())))
				break;
			
			if(hasCollisions(offsetBox))
			{
				double subBlockOffset = getSubBlockOffset(offsetBox);
				if(subBlockOffset >= 1
					|| Math.abs(height) + subBlockOffset > SCAN_RANGE)
					continue;
				
				AABB newOffsetBox = offsetBox.move(0, subBlockOffset, 0);
				if(hasCollisions(newOffsetBox))
					continue;
				
				height += subBlockOffset;
				offsetBox = newOffsetBox;
			}
			
			if(!hasCollisions(box.minmax(offsetBox)))
				continue;
			
			return height;
		}
		
		throw new CmdError("There are no free blocks where you can fit!");
	}
	
	private boolean hasCollisions(AABB box)
	{
		return BlockUtils.getBlockCollisions(box).findAny().isPresent();
	}
	
	private double getSubBlockOffset(AABB offsetBox)
	{
		return BlockUtils.getBlockCollisions(offsetBox)
			.mapToDouble(box -> box.maxY).max().getAsDouble() - offsetBox.minY;
	}
	
	private void vclip(double height)
	{
		LocalPlayer p = MC.player;
		
		// The server only accepts so much movement per packet, but it scales
		// that limit with the number of packets received. Sending filler
		// packets first and the real position last lets us go further.
		int packets =
			Math.max(1, (int)Math.ceil(Math.abs(height) / BLOCKS_PER_PACKET));
		
		Entity vehicle = p.getVehicle();
		if(vehicle != null)
		{
			for(int i = 0; i < packets - 1; i++)
				p.connection
					.send(ServerboundMoveVehiclePacket.fromEntity(vehicle));
			
			vehicle.setPos(vehicle.getX(), vehicle.getY() + height,
				vehicle.getZ());
			p.connection.send(ServerboundMoveVehiclePacket.fromEntity(vehicle));
			return;
		}
		
		double x = p.getX();
		double y = p.getY();
		double z = p.getZ();
		boolean hCollision = p.horizontalCollision;
		
		// Claiming to be on the ground keeps the server from counting the
		// distance as a fall.
		for(int i = 0; i < packets - 1; i++)
			p.connection.send(
				new ServerboundMovePlayerPacket.StatusOnly(true, hCollision));
		p.connection.send(new ServerboundMovePlayerPacket.Pos(x, y + height, z,
			true, hCollision));
		
		p.setPos(x, y + height, z);
		p.resetFallDistance();
		if(p.getDeltaMovement().y < 0)
			p.setDeltaMovement(p.getDeltaMovement().x, 0,
				p.getDeltaMovement().z);
	}
}
