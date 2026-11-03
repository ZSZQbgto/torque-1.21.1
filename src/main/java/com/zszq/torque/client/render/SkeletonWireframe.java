package com.zszq.torque.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zszq.torque.client.bedrock.Skeleton;
import com.zszq.torque.client.bedrock.SkeletonPose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * F3 骨骼框架：把骨架画成世界空间的线段（父 pivot → 子 pivot）和 pivot 点。
 *
 * <p>低耦合：只依赖 {@link Skeleton} / {@link SkeletonPose} 和 {@link VertexConsumer}，
 * 不依赖实体或具体 RenderType。调用方传一个 {@code geoToWorld}（geo 格坐标 → 世界坐标）
 * 和已经摆好的 {@link PoseStack.Pose}，本类逐顶点乘好再提交。
 *
 * <p>用法（在玩家皮肤层里，PoseStack 已经带着实体位移/朝向）：
 * {@code SkeletonWireframe.emit(lines, poseStack.last(), skeleton, pose, new Matrix4f(), color)}
 * ——此时 geoToWorld 传单位矩阵即可，因为 Pose 里已经含 geo→model 与实体变换。
 */
public final class SkeletonWireframe {
    /** 根骨骼红色、普通骨骼金色、pivot 点白色 */
    private static final int ROOT_COLOR = 0xFFFF4040;
    private static final int JOINT_COLOR = 0xFFFFFFFF;
    /** pivot 十字线半长（格） */
    private static final float MARKER = 0.03F;

    private static final Vector3f POINT_A = new Vector3f();
    private static final Vector3f POINT_B = new Vector3f();

    private SkeletonWireframe() {
    }

    /**
     * @param geoToWorld geo 空间（格）→ 世界/模型空间的矩阵；在皮肤层里 Pose 已经含换算时传单位矩阵
     * @param color      普通骨骼颜色（ARGB）
     */
    public static void emit(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Skeleton skeleton,
            SkeletonPose skelPose,
            Matrix4f geoToWorld,
            int color) {
        for (int boneIndex = 0; boneIndex < skeleton.boneCount(); boneIndex++) {
            Vector3f pivot = skelPose.worldPivot(boneIndex, null);

            int parent = skeleton.parentOf(boneIndex);
            if (parent >= 0) {
                Vector3f parentPivot = skelPose.worldPivot(parent, null);
                int boneColor = parent == 0 ? ROOT_COLOR : color;
                line(buffer, pose, geoToWorld, parentPivot, pivot, boneColor);
            }

            marker(buffer, pose, geoToWorld, pivot, JOINT_COLOR);
        }
    }

    private static void marker(VertexConsumer buffer, PoseStack.Pose pose, Matrix4f geoToWorld, Vector3f pivot, int color) {
        POINT_A.set(pivot).add(-MARKER, 0.0F, 0.0F);
        POINT_B.set(pivot).add(MARKER, 0.0F, 0.0F);
        line(buffer, pose, geoToWorld, POINT_A, POINT_B, color);

        POINT_A.set(pivot).add(0.0F, -MARKER, 0.0F);
        POINT_B.set(pivot).add(0.0F, MARKER, 0.0F);
        line(buffer, pose, geoToWorld, POINT_A, POINT_B, color);

        POINT_A.set(pivot).add(0.0F, 0.0F, -MARKER);
        POINT_B.set(pivot).add(0.0F, 0.0F, MARKER);
        line(buffer, pose, geoToWorld, POINT_A, POINT_B, color);
    }

    private static void line(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Matrix4f geoToWorld,
            Vector3f from,
            Vector3f to,
            int color) {
        // geo 空间的点先乘 geoToWorld，再交给 Pose（Pose 里是 model→世界）
        POINT_A.set(from);
        geoToWorld.transformPosition(POINT_A);
        POINT_B.set(to);
        geoToWorld.transformPosition(POINT_B);

        buffer.addVertex(pose, POINT_A.x(), POINT_A.y(), POINT_A.z())
                .setColor(color)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
        buffer.addVertex(pose, POINT_B.x(), POINT_B.y(), POINT_B.z())
                .setColor(color)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
