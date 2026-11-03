package com.zszq.torque.client.bedrock;

import java.util.Map;
import java.util.Set;

/**
 * 一份 rig 绑定（{@code assets/<namespace>/rigs/**.json}）：把"语义槽位"映射到动画、几何体、物品骨骼。
 *
 * <p>语义槽位固定是 idle / walk / run / sneak / sneak_walk / fly / hover（见
 * {@code docs/animation-engine.md} §1）。代码只认槽位名，具体配哪条动画完全由资源包决定，不写死。
 *
 * <p>{@link #EMPTY} 用于"资源没加载到"的情况：{@code geometry()} 为 null，
 * {@code animations()} 为空表，其余字段取文档里的缺省值。
 */
public record RigDefinition(
        String geometry,
        Map<String, RigAnimation> animations,
        String rightItemBone,
        String leftItemBone,
        float walkPositionPerCycle,
        Set<String> hideVanilla) {

    /** 缺省值：没有 rig 可用时玩家退回静止姿势。 */
    public static final RigDefinition EMPTY = new RigDefinition(
            null,
            Map.of(),
            "rightItem",
            "leftItem",
            9.4333F,
            Set.of());

    public RigDefinition {
        animations = animations == null ? Map.of() : Map.copyOf(animations);
        rightItemBone = rightItemBone == null || rightItemBone.isBlank() ? "rightItem" : rightItemBone;
        leftItemBone = leftItemBone == null || leftItemBone.isBlank() ? "leftItem" : leftItemBone;
        walkPositionPerCycle = walkPositionPerCycle > 0.0F ? walkPositionPerCycle : 9.4333F;
        hideVanilla = hideVanilla == null ? Set.of() : Set.copyOf(hideVanilla);
    }

    /** 按语义槽位取绑定，没有返回 null。 */
    public RigAnimation slot(String slot) {
        if (slot == null) {
            return null;
        }

        return this.animations.get(slot);
    }
}
