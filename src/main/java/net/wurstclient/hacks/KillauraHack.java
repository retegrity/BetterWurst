/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.InteractionHand;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.AttackUtils;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"kill aura", "ForceField", "force field", "CrystalAura",
	"crystal aura", "AutoCrystal", "auto crystal"})
public final class KillauraHack extends Hack
	implements UpdateListener, HandleInputListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Attack range",
		"Determines how far Killaura will reach to attack entities.\n"
			+ "Anything that is further away than the specified value will not be attacked.",
		5, 1, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting targetRange = new SliderSetting("Target range",
		"How many blocks beyond the attack range Killaura will already turn"
			+ " towards entities.\n\n"
			+ "Entities in this extra range are not attacked yet, but your"
			+ " rotation is already facing them when they come into attack"
			+ " range.",
		0, 0, 5, 0.05,
		ValueDisplay.DECIMAL.withSuffix(" blocks").withLabel(0, "off"));
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SliderSetting speedRandMS =
		new SliderSetting("Speed randomization",
			"Helps you bypass anti-cheat plugins by varying the delay between"
				+ " attacks.\n\n" + "\u00b1100ms is recommended for Vulcan.\n\n"
				+ "0 (off) is fine for AAC, Grim, Verus, Spartan, and vanilla"
				+ " servers.",
			100, 0, 1000, 50, ValueDisplay.INTEGER.withPrefix("\u00b1")
				.withSuffix("ms").withLabel(0, "off"));
	
	private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
		"Determines which entity will be attacked first.\n"
			+ "\u00a7lDistance\u00a7r - Attacks the closest entity.\n"
			+ "\u00a7lAngle\u00a7r - Attacks the entity that requires the least head movement.\n"
			+ "\u00a7lHealth\u00a7r - Attacks the weakest entity.",
		Priority.values(), Priority.ANGLE);
	
	private final SliderSetting fov =
		new SliderSetting("FOV", 360, 30, 360, 10, ValueDisplay.DEGREES);
	
	private final EnumSetting<RotationMode> rotationMode = new EnumSetting<>(
		"Rotation",
		"\u00a7lHuman\u00a7r turns towards the target like a mouse would:"
			+ " it speeds up and slows down smoothly, lags a little behind"
			+ " the target, moves around on its hitbox and only attacks when"
			+ " it's actually looking at the target (otherwise it misses, like"
			+ " a real click). Needed for anti-cheats like Intave.\n\n"
			+ "\u00a7lInstant\u00a7r faces the target in a single tick and"
			+ " always hits.",
		RotationMode.values(), RotationMode.HUMAN);
	
	private final SliderSetting turnSpeed = new SliderSetting("Turn speed",
		"The fastest that \u00a7lHuman\u00a7r rotation turns.", 40, 10, 90, 1,
		ValueDisplay.DEGREES.withSuffix("/tick"));
	
	private final SliderSetting acceleration = new SliderSetting("Acceleration",
		"How quickly \u00a7lHuman\u00a7r rotation can speed up or slow"
			+ " down. Above 20 it would snap, which Intave flags.",
		15, 5, 20, 1, ValueDisplay.DEGREES.withSuffix("/tick\u00b2"));
	
	private final SliderSetting missChance = new SliderSetting("Miss chance",
		"How often \u00a7lHuman\u00a7r rotation misses on purpose, even when"
			+ " it's looking at the target. Intave flags you if fewer than 5%"
			+ " of your swings miss.",
		0.08, 0, 0.3, 0.01, ValueDisplay.PERCENTAGE);
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final CheckboxSetting damageIndicator = new CheckboxSetting(
		"Damage indicator",
		"Renders a colored box within the target, inversely proportional to its remaining health.",
		true);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final CheckboxSetting checkLOS =
		new CheckboxSetting("Check line of sight",
			"Ensures that you don't reach through blocks when attacking.\n\n"
				+ "Slower but can help with anti-cheat plugins.",
			false);
	
	private final CheckboxSetting movementCorrection = new CheckboxSetting(
		"Movement correction",
		"Calculates your movement from the rotation that the server sees"
			+ " instead of where your camera points, and adjusts your keys so"
			+ " you still walk the way you intended.\n\n"
			+ "Helps against anti-cheat plugins that check whether your"
			+ " movement matches your rotation.",
		false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	private static final int ROTATION_TICKS = 5;
	
	// how far from the center of the target's hitbox Human rotation can
	// still miss on purpose instead of not clicking at all
	private static final double MAX_MISS_ANGLE = 30;
	
	private Entity target;
	private Entity renderTarget;
	
	// Human rotation: where on the target's hitbox it's aiming, relative to
	// the center (side: -1 to 1, height: 0 to 1), and where it's heading
	private final Random random = new Random();
	private Entity aimEntity;
	private double aimSide;
	private double aimHeight;
	private double goalSide;
	private double goalHeight;
	private int goalTicks;
	
	public KillauraHack()
	{
		super("Killaura");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(targetRange);
		addSetting(speed);
		addSetting(speedRandMS);
		addSetting(priority);
		addSetting(fov);
		addSetting(rotationMode);
		addSetting(turnSpeed);
		addSetting(acceleration);
		addSetting(missChance);
		addSetting(swingHand);
		addSetting(damageIndicator);
		addSetting(pauseOnContainers);
		addSetting(checkLOS);
		addSetting(movementCorrection);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		speed.resetTimer(speedRandMS.getValue());
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		target = null;
		renderTarget = null;
		aimEntity = null;
		WURST.getRotationFaker().releaseManagedRotation();
	}
	
	@Override
	public void onUpdate()
	{
		speed.updateTimer();
		
		target = null;
		renderTarget = null;
		
		if(pauseOnContainers.shouldPause())
			return;
		
		// don't fight over the rotation with Clutch
		if(WURST.getHax().clutchHack.isClutching()
			|| WURST.getHax().anchorAuraHack.isBusy())
			return;
		
		Stream<Entity> stream = EntityUtils.getMeleeAttackableEntities();
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils.getAngleToLookVec(
				e.getBoundingBox().getCenter()) <= fov.getValue() / 2.0);
		
		List<Entity> entities = entityFilters.applyTo(stream).toList();
		
		// Entities within attack range come first. Entities in the extra
		// target range are only faced, not attacked.
		double attackRangeSq = range.getValueSq();
		double aimRange = range.getValue() + targetRange.getValue();
		double aimRangeSq = aimRange * aimRange;
		
		Comparator<Entity> comparator = priority.getSelected().comparator;
		Entity candidate = entities.stream()
			.filter(e -> EntityUtils.distanceToHitboxSq(e) <= attackRangeSq)
			.min(comparator).orElse(null);
		boolean inAttackRange = candidate != null;
		
		if(candidate == null)
			candidate = entities.stream()
				.filter(e -> EntityUtils.distanceToHitboxSq(e) <= aimRangeSq)
				.min(comparator).orElse(null);
		
		renderTarget = candidate;
		if(candidate == null)
			return;
		
		boolean human = rotationMode.getSelected() == RotationMode.HUMAN;
		
		// Instant: aim at the closest point of the hitbox
		// Human: aim at a point that moves around on the hitbox
		Vec3 hitVec = human ? getHumanAimPoint(candidate)
			: getClosestPoint(candidate.getBoundingBox());
		if(checkLOS.isChecked() && !BlockUtils.hasLineOfSight(hitVec))
			return;
		
		// keep looking at the target on every tick, not just when attacking
		if(human)
			WURST.getRotationFaker().faceVectorHumanly(hitVec,
				turnSpeed.getValueF(), acceleration.getValueF(), ROTATION_TICKS,
				movementCorrection.isChecked());
		else
			WURST.getRotationFaker().faceVectorSilently(hitVec, ROTATION_TICKS,
				movementCorrection.isChecked());
		
		if(!inAttackRange || !speed.isTimeToAttack())
			return;
		
		target = candidate;
		WURST.getHax().autoSwordHack.setSlot(target);
	}
	
	/**
	 * A point on the target's hitbox that drifts around smoothly, crossing
	 * from one side of the center to the other every few ticks. Aiming at a
	 * fixed point (like the closest one) keeps the angle to the center of the
	 * hitbox almost constant, which Intave's accuracy heuristics flag.
	 */
	private Vec3 getHumanAimPoint(Entity entity)
	{
		if(entity != aimEntity)
		{
			aimEntity = entity;
			aimSide = 0;
			aimHeight = 0.6;
			goalTicks = 0;
		}
		
		if(--goalTicks <= 0)
		{
			// alternate between the left and right side of the center
			double side = 0.2 + random.nextDouble() * 0.55;
			goalSide = goalSide > 0 ? -side : side;
			goalHeight = 0.25 + random.nextDouble() * 0.65;
			goalTicks = 3 + random.nextInt(5);
		}
		
		aimSide += (goalSide - aimSide) * 0.35;
		aimHeight += (goalHeight - aimHeight) * 0.35;
		
		// "side" is relative to the direction you are looking at it from
		AABB box = entity.getBoundingBox();
		Vec3 center = box.getCenter();
		Vec3 eyes = RotationUtils.getEyesPos();
		double dx = center.x - eyes.x;
		double dz = center.z - eyes.z;
		double length = Math.hypot(dx, dz);
		double sideX = length < 1e-4 ? 1 : -dz / length;
		double sideZ = length < 1e-4 ? 0 : dx / length;
		double halfWidth = box.getXsize() / 2;
		
		return new Vec3(center.x + sideX * aimSide * halfWidth,
			box.minY + aimHeight * box.getYsize(),
			center.z + sideZ * aimSide * halfWidth);
	}
	
	private Vec3 getClosestPoint(AABB box)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		
		// slightly smaller box, so the point is never on the very edge
		AABB inner = box.deflate(0.01);
		Vec3 closest = new Vec3(Mth.clamp(eyes.x, inner.minX, inner.maxX),
			Mth.clamp(eyes.y, inner.minY, inner.maxY),
			Mth.clamp(eyes.z, inner.minZ, inner.maxZ));
		
		// eyes inside the hitbox, so aim at the center
		if(closest.distanceToSqr(eyes) < 1e-8)
			return box.getCenter();
		
		return closest;
	}
	
	@Override
	public void onHandleInput()
	{
		if(target == null)
			return;
		
		if(WURST.getHax().criticalsHack.shouldDelayAttack())
			return;
		
		if(AttackUtils.shouldDelayAttack())
			return;
		
		if(rotationMode.getSelected() == RotationMode.HUMAN
			&& !shouldHumanAttack())
			return;
		
		AttackUtils.attackAndSwing(target,
			() -> swingHand.swing(InteractionHand.MAIN_HAND));
		
		target = null;
		speed.resetTimer(speedRandMS.getValue());
	}
	
	/**
	 * Clicks like a human: only hits if the rotation the server has right
	 * now is actually looking at the target. Otherwise (or sometimes on
	 * purpose) it only swings, which is a miss.
	 *
	 * @return true if it should attack
	 */
	private boolean shouldHumanAttack()
	{
		// the attack is sent before this tick's movement packet, so the
		// server checks it against the rotation from the last tick
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 look = new Rotation(WURST.getRotationFaker().getServerYaw(),
			WURST.getRotationFaker().getServerPitch()).toLookVec();
		
		if(isLookingAt(eyes, look, target)
			&& random.nextDouble() >= missChance.getValue())
			return true;
		
		// not looking anywhere near it, so don't click at all yet
		Vec3 toCenter = target.getBoundingBox().getCenter().subtract(eyes);
		double cos = look.dot(toCenter.normalize());
		if(Math.toDegrees(Math.acos(Mth.clamp(cos, -1, 1))) > MAX_MISS_ANGLE)
			return false;
		
		// miss, like a vanilla click on nothing
		swingHand.swing(InteractionHand.MAIN_HAND);
		MC.player.resetAttackStrengthTicker();
		target = null;
		speed.resetTimer(speedRandMS.getValue());
		return false;
	}
	
	private boolean isLookingAt(Vec3 eyes, Vec3 look, Entity entity)
	{
		AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
		if(box.contains(eyes))
			return true;
		
		Vec3 end = eyes.add(look.scale(range.getValue()));
		Vec3 hit = box.clip(eyes, end).orElse(null);
		if(hit == null)
			return false;
		
		return !checkLOS.isChecked() || BlockUtils.hasLineOfSight(hit);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(renderTarget == null || !damageIndicator.isChecked())
			return;
		
		float p = 1;
		if(renderTarget instanceof LivingEntity le && le.getMaxHealth() > 1e-5)
			p = 1 - le.getHealth() / le.getMaxHealth();
		float red = p * 2F;
		float green = 2 - red;
		float[] rgb = {red, green, 0};
		int quadColor = RenderUtils.toIntColor(rgb, 0.25F);
		int lineColor = RenderUtils.toIntColor(rgb, 0.5F);
		
		AABB box = EntityUtils.getLerpedBox(renderTarget, partialTicks);
		if(p < 1)
			box = box.deflate((1 - p) * 0.5 * box.getXsize(),
				(1 - p) * 0.5 * box.getYsize(), (1 - p) * 0.5 * box.getZsize());
		
		RenderUtils.drawSolidBox(matrixStack, box, quadColor, false);
		RenderUtils.drawOutlinedBox(matrixStack, box, lineColor, false);
	}
	
	private enum RotationMode
	{
		HUMAN("Human"),
		INSTANT("Instant");
		
		private final String name;
		
		private RotationMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Priority
	{
		DISTANCE("Distance", EntityUtils::distanceToHitboxSq),
		
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		
		HEALTH("Health", e -> e instanceof LivingEntity
			? ((LivingEntity)e).getHealth() : Integer.MAX_VALUE);
		
		private final String name;
		private final Comparator<Entity> comparator;
		
		private Priority(String name, ToDoubleFunction<Entity> keyExtractor)
		{
			this.name = name;
			comparator = Comparator.comparingDouble(keyExtractor);
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
