package com.zszq.torque.client.bedrock;

/**
 * 驱动一条动画的时钟类型（见 {@code docs/animation-engine.md} §1 的 rig 约定）。
 *
 * <ul>
 *   <li>{@link #TIME}：按秒循环，时间 = {@code (tickCount + partialTick) / 20}；</li>
 *   <li>{@link #WALK_POSITION}：按行走距离驱动，时间 =
 *       {@code walkAnimation.position(partialTick) / walkPositionPerCycle}，
 *       这样脚步的相位跟实体实际移动同步，不会出现"滑步"。</li>
 * </ul>
 */
public enum AnimationClock {
    TIME,
    WALK_POSITION
}
