package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zszq.torque.client.bedrock.AnimationClock;
import com.zszq.torque.client.bedrock.AnimationLibrary;
import com.zszq.torque.client.bedrock.BedrockAnimation;
import com.zszq.torque.client.bedrock.BedrockGeometry;
import com.zszq.torque.client.bedrock.GeometryLibrary;
import com.zszq.torque.client.bedrock.RigAnimation;
import com.zszq.torque.client.bedrock.RigDefinition;
import com.zszq.torque.client.bedrock.RigLibrary;
import com.zszq.torque.client.bedrock.Skeleton;
import com.zszq.torque.client.bedrock.SkeletonPose;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;

/**
 * 玩家 → 资源包动画的绑定层（本 mod 特有逻辑；引擎本身在 {@code client.bedrock}）。
 *
 * <p>职责只有三件：
 * <ol>
 *     <li>从 {@link RigLibrary#player()} 拿到“语义槽位 → 动画名”的绑定（数据，不在代码里写死）；</li>
 *     <li>把实体状态折算成各槽位的混合权重（走 / 跑 / 潜行 / 飞行……），只给实际加载到的动画计权重；</li>
 *     <li>每帧把权重交给 {@link SkeletonPose} 混合，并缓存骨架 / 姿态对象避免每帧分配。</li>
 * </ol>
 *
 * <p>时钟：{@code walk_position} 槽位用 {@code LivingEntity#walkAnimation.position}
 * 除以 {@code walkPositionPerCycle} 得到循环数，再乘动画长度；{@code time} 槽位用
 * {@code (tickCount + partialTick) / 20} 秒。这样走路 / 跑步的循环和脚步同步，
 * 站立的待机动作也不会因为“没位移”而冻住。
 */
public final class PlayerRig {
    public static final PlayerRig INSTANCE = new PlayerRig();

    /** 语义槽位名（与 rig json 的 animations 键一致） */
    private static final String[] SLOTS = {"idle", "walk", "run", "sneak", "sneak_walk", "fly", "hover"};
    private static final int IDLE = 0;
    private static final int WALK = 1;
    private static final int RUN = 2;
    private static final int SNEAK = 3;
    private static final int SNEAK_WALK = 4;
    private static final int FLY = 5;
    private static final int HOVER = 6;

    /** 水平速度（格/tick）到这个值算完全在移动：原版走 ~0.216、潜行 ~0.065 */
    private static final float MOVE_RAMP = 0.08F;
    /** 走 / 跑过渡区间（格/tick）：原版走路 ~0.216、冲刺 ~0.28 */
    private static final float RUN_MIN_SPEED = 0.23F;
    private static final float RUN_MAX_SPEED = 0.27F;

    /** 每个实体的姿态缓存，渲染线程访问 */
    private final Map<Integer, SkeletonPose> poses = new HashMap<>();

    private Skeleton skeleton;
    private BedrockGeometry skeletonGeometry;

    private PlayerRig() {
    }

    // ------------------------------------------------------------------ 数据

    public RigDefinition rig() {
        return RigLibrary.player();
    }

    /** 当前的统一骨骼；几何体没加载到（或 rig 没配置）时返回 null。 */
    public Skeleton skeleton() {
        RigDefinition rig = this.rig();
        BedrockGeometry geometry = GeometryLibrary.get(rig.geometry());
        if (geometry == null) {
            this.skeleton = null;
            this.skeletonGeometry = null;
            return null;
        }

        if (this.skeleton == null || this.skeletonGeometry != geometry) {
            this.skeleton = Skeleton.of(geometry);
            this.skeletonGeometry = geometry;
            // 几何体换了（资源重载）：旧姿态引用的是旧骨骼数组，全部丢掉
            this.poses.clear();
        }

        return this.skeleton;
    }

    /** 手持物品占位骨骼：这些骨骼上的 cube 由 {@link SkinnedPlayerLayer} 跳过。 */
    public Set<String> hiddenBones() {
        RigDefinition rig = this.rig();
        Set<String> hidden = new HashSet<>(2);
        if (rig.rightItemBone() != null) {
            hidden.add(rig.rightItemBone());
        }
        if (rig.leftItemBone() != null) {
            hidden.add(rig.leftItemBone());
        }
        return hidden;
    }

    // ------------------------------------------------------------------ 姿态

    /**
     * 取这个玩家这一帧的骨骼姿态（缓存并复用 {@link SkeletonPose} 对象）。
     * 数据没加载好 / 没有几何体时返回 null，调用方直接不画即可。
     */
    public SkeletonPose poseFor(AbstractClientPlayer player, float partialTick) {
        Skeleton current = this.skeleton();
        if (current == null) {
            return null;
        }

        SkeletonPose pose = this.poses.get(player.getId());
        if (pose == null || pose.skeleton() != current) {
            pose = new SkeletonPose(current);
            // 玩家多、实体 id 不断增长时避免 map 无限膨胀
            if (this.poses.size() > 512) {
                this.poses.clear();
            }
            this.poses.put(player.getId(), pose);
        }

        pose.clear();

        float[] weights = this.weights(player, partialTick);
        for (int slot = 0; slot < SLOTS.length; slot++) {
            if (weights[slot] <= 0.0F) {
                continue;
            }

            RigAnimation ref = this.rig().slot(SLOTS[slot]);
            if (ref == null) {
                continue;
            }

            BedrockAnimation animation = AnimationLibrary.get(ref.clip());
            if (animation == null) {
                continue;
            }

            pose.addAnimation(animation, time(animation, ref.clock(), player, partialTick), weights[slot]);
        }

        pose.finish();
        return pose;
    }

    /** 各槽位权重（只给加载到动画的槽位计权重，剩余部分留给几何体静止姿势）。 */
    private float[] weights(AbstractClientPlayer player, float partialTick) {
        RigDefinition rig = this.rig();
        float[] weights = new float[SLOTS.length];

        boolean hasIdle = available(rig, "idle");
        boolean hasWalk = available(rig, "walk");
        boolean hasRun = available(rig, "run");
        boolean hasSneak = available(rig, "sneak") || available(rig, "sneak_walk");
        boolean hasFly = available(rig, "fly");
        boolean hasHover = available(rig, "hover");

        float speed = (float) player.getDeltaMovement().horizontalDistance();
        float move = Mth.clamp(speed / MOVE_RAMP, 0.0F, 1.0F);
        float animSpeed = player.walkAnimation.speed(partialTick);
        // 远程玩家/被推动时 deltaMovement 可能还没更新，用原版 walkAnimation 兜底一次
        if (move < 0.05F) {
            move = Mth.clamp(animSpeed * 1.2F, 0.0F, 1.0F);
        }

        float run = Mth.clamp((speed - RUN_MIN_SPEED) / (RUN_MAX_SPEED - RUN_MIN_SPEED), 0.0F, 1.0F);
        if (player.isSprinting()) {
            run = 1.0F;
        }

        boolean sneak = hasSneak && player.isCrouching();
        boolean fallFlying = player.isFallFlying();
        boolean flying = !fallFlying
                && player.getAbilities().flying
                && !player.isSpectator();

        // 飞行是覆盖状态：一旦接管，地面动作整体后退
        float flyWeight = fallFlying && hasFly ? 1.0F : 0.0F;
        float hoverWeight = flying && hasHover ? 1.0F : 0.0F;
        float ground = (1.0F - flyWeight) * (1.0F - hoverWeight);
        float moving = move * ground;

        float sneakFactor = sneak ? 1.0F : 0.0F;
        if (hasIdle) {
            weights[IDLE] = (1.0F - move) * (1.0F - sneakFactor) * ground;
        }
        if (hasWalk) {
            weights[WALK] = moving * (1.0F - run) * (1.0F - sneakFactor);
        }
        if (hasRun) {
            weights[RUN] = moving * run * (1.0F - sneakFactor);
        }
        if (sneak) {
            weights[SNEAK] = (1.0F - move) * ground;
            weights[SNEAK_WALK] = moving;
        }
        weights[FLY] = flyWeight;
        weights[HOVER] = hoverWeight;

        // 没有动画的槽位清零（权重留给静止姿势），并把总和压到 <= 1
        float total = 0.0F;
        for (int slot = 0; slot < SLOTS.length; slot++) {
            if (!available(rig, SLOTS[slot])) {
                weights[slot] = 0.0F;
            }
            total += weights[slot];
        }

        if (total > 1.0F) {
            for (int slot = 0; slot < SLOTS.length; slot++) {
                weights[slot] /= total;
            }
        }

        return weights;
    }

    private static boolean available(RigDefinition rig, String slot) {
        RigAnimation ref = rig.slot(slot);
        return ref != null && AnimationLibrary.get(ref.clip()) != null;
    }

    private static float time(BedrockAnimation animation, AnimationClock clock, AbstractClientPlayer player, float partialTick) {
        if (clock == AnimationClock.WALK_POSITION) {
            float cycleUnits = Math.max(0.001F, INSTANCE.rig().walkPositionPerCycle());
            float position = player.walkAnimation.position(partialTick);
            return animation.wrap(position / cycleUnits * animation.length());
        }

        return animation.wrap((player.tickCount + partialTick) / 20.0F);
    }

    // ------------------------------------------------------------------ 姿态应用

    /**
     * 把某根骨骼（含所有祖先）的局部变换压进 PoseStack，调用后 PoseStack 正好落在该骨骼的
     * pivot 上，可直接在该位置挂手持物品等。
     *
     * <p>局部变换（geo 空间，像素→格）：{@code T(pivot+pos) · Rz·Ry·Rx · S · T(-pivot)}，
     * 旋转顺序 ZYX 与 Bedrock / 原版 {@code ModelPart#rotationZYX} 一致。
     */
    public static void applyBoneChain(PoseStack poseStack, Skeleton skeleton, SkeletonPose pose, int boneIndex) {
        int depth = 0;
        for (int bone = boneIndex; bone >= 0; bone = skeleton.parentOf(bone)) {
            depth++;
        }

        int[] chain = new int[depth];
        int cursor = depth;
        for (int bone = boneIndex; bone >= 0; bone = skeleton.parentOf(bone)) {
            chain[--cursor] = bone;
        }

        for (int bone : chain) {
            float[] pivot = skeleton.pivot(bone);
            float[] position = pose.position(bone);
            float[] rotation = pose.rotation(bone);
            float[] scale = pose.scale(bone);

            poseStack.translate(
                    (pivot[0] + position[0]) / 16.0F,
                    (pivot[1] + position[1]) / 16.0F,
                    (pivot[2] + position[2]) / 16.0F);

            float rx = (float) Math.toRadians(rotation[0]);
            float ry = (float) Math.toRadians(rotation[1]);
            float rz = (float) Math.toRadians(rotation[2]);
            // PoseStack 的 mulPose 是右乘；按 Rx → Ry → Rz 顺序乘出来是 Rz·Ry·Rx
            poseStack.mulPose(Axis.XP.rotation(rx));
            poseStack.mulPose(Axis.YP.rotation(ry));
            poseStack.mulPose(Axis.ZP.rotation(rz));

            if (scale[0] != 1.0F || scale[1] != 1.0F || scale[2] != 1.0F) {
                poseStack.scale(scale[0], scale[1], scale[2]);
            }

            poseStack.translate(-pivot[0] / 16.0F, -pivot[1] / 16.0F, -pivot[2] / 16.0F);
        }
    }
}
