package com.elytramace.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

/**
 * Elytra + Mace dive-bomb module.
 * <p>
 * Settings and behaviour ported from the reference addon (elytra-mace-addon, MC 1.21.11)
 * to Minecraft 26.2 / mojmap.
 */
public class ElytraMace extends Module {
    private static final int CHESTPLATE_SLOT = 6;
    private static final int ACTIVATION_RETRY_DELAY = 5;
    private static final int ASCENDING_TIMEOUT_TICKS = 120;

    private State state = State.IDLE;
    private double diveStartY = 0.0;
    private boolean elytraRemoved = false;
    private int cooldownTicksLeft = 0;
    private int rocketCooldownTicks = 0;
    private int activationRetryTicks = 0;
    private int ascendingTimeout = 0;
    private LivingEntity target = null;
    private float savedPitch = 0.0f;
    private float savedYaw = 0.0f;
    private boolean rotationOverridden = false;

    private final SettingGroup sgTargets = this.settings.createGroup("Targets");
    private final SettingGroup sgFlight = this.settings.createGroup("Flight");

    // Targets

    private final Setting<Boolean> targetPlayers = sgTargets.add(new BoolSetting.Builder()
        .name("players")
        .description("Target other players.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> targetMonsters = sgTargets.add(new BoolSetting.Builder()
        .name("monsters")
        .description("Target hostile mobs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> targetAnimals = sgTargets.add(new BoolSetting.Builder()
        .name("animals")
        .description("Target passive animals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> targetNeutrals = sgTargets.add(new BoolSetting.Builder()
        .name("neutrals")
        .description("Target neutral mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> targetBosses = sgTargets.add(new BoolSetting.Builder()
        .name("bosses")
        .description("Target boss entities.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> targetRange = sgTargets.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Maximum range to search for targets.")
        .defaultValue(60.0)
        .min(1.0)
        .sliderMax(60.0)
        .build()
    );

    // Flight

    private final Setting<Double> ascentHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("ascent-height")
        .description("How many blocks above the target's Y to ascend before diving.")
        .defaultValue(8.5)
        .min(2.0)
        .sliderMax(40.0)
        .build()
    );

    private final Setting<Double> attackCooldown = sgFlight.add(new DoubleSetting.Builder()
        .name("attack-cooldown")
        .description("Delay in seconds after a mace hit before starting the next cycle.")
        .defaultValue(0.05)
        .min(0.05)
        .sliderMax(1.0)
        .build()
    );

    public ElytraMace() {
        super(Categories.Combat, "elytra-mace", "Fly up with an elytra and dive-bomb targets with a mace.");
    }

    @Override
    public void onDeactivate() {
        restoreRotation();
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (!hasRequiredItems()) {
            if (state != State.IDLE) resetState();
            return;
        }

        target = findTarget();
        if (target == null) {
            resetState();
            return;
        }

        switch (state) {
            case IDLE -> handleIdle();
            case ASCENDING -> handleAscending();
            case DIVING -> handleDiving();
            case COOLDOWN -> handleCooldown();
        }
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        restoreRotation();
    }

    private void handleIdle() {
        if (ensureElytraEquipped()) {
            rocketCooldownTicks = 0;
            elytraRemoved = false;
            activationRetryTicks = 0;
            ascendingTimeout = 0;
            state = State.ASCENDING;
        }
    }

    private void handleAscending() {
        double desiredY = target.getY() + ascentHeight.get();
        ascendingTimeout++;

        if (ascendingTimeout > ASCENDING_TIMEOUT_TICKS) {
            resetState();
            return;
        }

        if (!ensureElytraEquipped()) return;

        if (!mc.player.isFallFlying()) {
            if (mc.player.onGround()) {
                mc.player.jumpFromGround();
                activationRetryTicks = 0;
            } else if (activationRetryTicks > 0) {
                activationRetryTicks--;
            } else {
                overrideRotation((float) Rotations.getYaw(target), calculateAscentPitch());
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
                activationRetryTicks = ACTIVATION_RETRY_DELAY;
                if (rocketCooldownTicks <= 0) fireRocket();
            }
        } else {
            ascendingTimeout = 0;
            activationRetryTicks = 0;
            overrideRotation((float) Rotations.getYaw(target), calculateAscentPitch());

            rocketCooldownTicks = Math.max(0, rocketCooldownTicks - 1);
            if (rocketCooldownTicks == 0) fireRocket();

            if (mc.player.getY() >= desiredY - 0.5) {
                ascendingTimeout = 0;
                rocketCooldownTicks = 0;
                state = State.DIVING;
                diveStartY = mc.player.getY();
                elytraRemoved = false;
            }
        }
    }

    private void handleDiving() {
        overrideRotation((float) Rotations.getYaw(target), (float) Rotations.getPitch(target));

        double distToTarget = mc.player.distanceTo(target);
        boolean isFalling = mc.player.getDeltaMovement().y < 0.0;

        if (distToTarget > 6.0) {
            rocketCooldownTicks = Math.max(0, rocketCooldownTicks - 1);
            if (rocketCooldownTicks == 0) fireRocket();
        }

        if (!elytraRemoved && isFalling && distToTarget <= 8.0) {
            equipMaceInHotbar();
            removeElytra();
            elytraRemoved = true;
        }

        if (isFalling && distToTarget <= 4.5) {
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
            cooldownTicksLeft = Math.max(1, (int) Math.ceil(attackCooldown.get() * 20.0));
            state = State.COOLDOWN;
        } else if (mc.player.onGround()) {
            if (target.isAlive()) {
                rocketCooldownTicks = 0;
                elytraRemoved = false;
                diveStartY = 0.0;
                state = State.IDLE;
            } else {
                resetState();
            }
        }
    }

    private void handleCooldown() {
        cooldownTicksLeft--;
        if (cooldownTicksLeft > 0) return;

        if (target == null || !target.isAlive()) {
            resetState();
            return;
        }

        rocketCooldownTicks = 0;
        elytraRemoved = false;
        diveStartY = 0.0;
        activationRetryTicks = 0;
        ascendingTimeout = 0;

        if (mc.player.onGround()) {
            state = State.IDLE;
            return;
        }

        double altAboveTarget = mc.player.getY() - target.getY();
        state = State.ASCENDING;

        if (altAboveTarget >= ascentHeight.get() && ensureElytraEquipped()) {
            if (!mc.player.isFallFlying()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
                ));
            }
            state = State.DIVING;
            diveStartY = mc.player.getY();
        }
    }

    private void fireRocket() {
        int rocketSlot = findRocketSlot();
        if (rocketSlot == -1) {
            rocketCooldownTicks = 10;
            return;
        }

        ItemStack rocketStack = mc.player.getInventory().getItem(rocketSlot);
        int duration = calculateRocketDuration(rocketStack);

        InvUtils.swap(rocketSlot, false);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InvUtils.swapBack();

        rocketCooldownTicks = duration;
    }

    private int calculateRocketDuration(ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() == Items.FIREWORK_ROCKET) {
            Fireworks fw = stack.get(DataComponents.FIREWORKS);
            return fw != null && fw.flightDuration() > 0 ? fw.flightDuration() * 20 : 20;
        }
        return 20;
    }

    private float calculateAscentPitch() {
        if (target == null) return -89.9f;

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double neededHeight = target.getY() + ascentHeight.get() - mc.player.getY();

        if (horizontalDist < 2.0) return -89.9f;

        double elevAngle = Math.toDegrees(Math.atan2(neededHeight, horizontalDist));
        return (float) Math.max(-85.0, Math.min(-20.0, -elevAngle));
    }

    private boolean hasRequiredItems() {
        Inventory inv = mc.player.getInventory();
        boolean hasElytra = false;
        boolean hasRocket = false;
        boolean hasMace = false;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() == Items.ELYTRA) hasElytra = true;
            if (stack.getItem() == Items.FIREWORK_ROCKET) hasRocket = true;
            if (stack.getItem() == Items.MACE) hasMace = true;
        }

        return hasElytra && hasRocket && hasMace;
    }

    private boolean ensureElytraEquipped() {
        if (state == State.DIVING) return true;

        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.isEmpty() && isElytra(chest)) return true;

        Inventory inv = mc.player.getInventory();
        int containerSlot = -1;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && isElytra(s)) {
                containerSlot = i < 9 ? i + 36 : i;
                break;
            }
        }

        if (containerSlot == -1) return false;

        int syncId = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(syncId, containerSlot, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(syncId, CHESTPLATE_SLOT, 0, ContainerInput.PICKUP, mc.player);

        ItemStack cursor = mc.player.inventoryMenu.getCarried();
        if (!cursor.isEmpty()) {
            mc.gameMode.handleContainerInput(syncId, containerSlot, 0, ContainerInput.PICKUP, mc.player);
        }

        return true;
    }

    private void removeElytra() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty() || !isElytra(chest)) return;

        Inventory inv = mc.player.getInventory();
        int freeInvSlot = -1;

        for (int i = 9; i <= 35; i++) {
            if (inv.getItem(i).isEmpty()) {
                freeInvSlot = i;
                break;
            }
        }

        if (freeInvSlot == -1) return;

        int syncId = mc.player.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(syncId, CHESTPLATE_SLOT, 0, ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(syncId, freeInvSlot, 0, ContainerInput.PICKUP, mc.player);
    }

    private int findRocketSlot() {
        Inventory inv = mc.player.getInventory();

        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != Items.FIREWORK_ROCKET) continue;

            Fireworks fw = s.get(DataComponents.FIREWORKS);
            if (fw != null && fw.flightDuration() > 0) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == Items.FIREWORK_ROCKET) return i;
        }

        int rocketInvSlot = -1;
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == Items.FIREWORK_ROCKET) {
                rocketInvSlot = i;
                break;
            }
        }

        if (rocketInvSlot == -1) return -1;

        int targetHotbar = 8;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }

        mc.gameMode.handleContainerInput(
            mc.player.inventoryMenu.containerId, rocketInvSlot, targetHotbar, ContainerInput.SWAP, mc.player
        );
        return targetHotbar;
    }

    private boolean equipMaceInHotbar() {
        if (mc.player.getMainHandItem().getItem() == Items.MACE) return true;

        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == Items.MACE) {
                InvUtils.swap(i, false);
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(i));
                return true;
            }
        }

        return false;
    }

    private LivingEntity findTarget() {
        if (targetPlayers.get()) {
            Player p = TargetUtils.getPlayerTarget(targetRange.get(), SortPriority.LowestDistance);
            if (p != null) return p;
        }

        return (LivingEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof LivingEntity le)) return false;
            if (!le.isAlive() || le.isDeadOrDying()) return false;
            if (le == mc.player) return false;
            if (mc.player.distanceTo(le) > targetRange.get()) return false;

            if (entity instanceof Monster && targetMonsters.get()) return true;
            if (entity instanceof Animal && targetAnimals.get()) return true;
            if (entity instanceof Mob && !(entity instanceof Monster) && !(entity instanceof Animal)
                && targetNeutrals.get()) return true;
            if (entity instanceof EnderDragon || entity instanceof WitherBoss) return targetBosses.get();

            return false;
        }, SortPriority.LowestDistance);
    }

    private boolean isElytra(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == Items.ELYTRA;
    }

    private void overrideRotation(float yaw, float pitch) {
        if (!rotationOverridden) {
            savedPitch = mc.player.getXRot();
            savedYaw = mc.player.getYRot();
            rotationOverridden = true;
        }

        mc.player.setXRot(pitch);
        mc.player.setYRot(yaw);
    }

    private void restoreRotation() {
        if (rotationOverridden && mc.player != null) {
            mc.player.setXRot(savedPitch);
            mc.player.setYRot(savedYaw);
            rotationOverridden = false;
        }
    }

    private void resetState() {
        state = State.IDLE;
        target = null;
        rocketCooldownTicks = 0;
        elytraRemoved = false;
        cooldownTicksLeft = 0;
        activationRetryTicks = 0;
        ascendingTimeout = 0;
        diveStartY = 0.0;
    }

    private enum State {
        IDLE,
        ASCENDING,
        DIVING,
        COOLDOWN
    }
}
