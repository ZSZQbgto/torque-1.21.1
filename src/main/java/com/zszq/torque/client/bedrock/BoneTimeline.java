package com.zszq.torque.client.bedrock;

/**
 * 一根骨骼上的三条通道（rotation / position / scale）。
 *
 * <p>动画里没有写到的通道用 {@link AnimationChannel#EMPTY} 表示，混合规则见
 * {@link SkeletonPose#addAnimation(BedrockAnimation, float, float)}。
 */
public record BoneTimeline(AnimationChannel rotation, AnimationChannel position, AnimationChannel scale) {
    /** 三条通道都为空。 */
    public static final BoneTimeline EMPTY = new BoneTimeline(AnimationChannel.EMPTY, AnimationChannel.EMPTY, AnimationChannel.EMPTY);

    public BoneTimeline {
        rotation = rotation == null ? AnimationChannel.EMPTY : rotation;
        position = position == null ? AnimationChannel.EMPTY : position;
        scale = scale == null ? AnimationChannel.EMPTY : scale;
    }

    /** 三条通道都没有关键帧。 */
    public boolean isEmpty() {
        return this.rotation.isEmpty() && this.position.isEmpty() && this.scale.isEmpty();
    }
}
