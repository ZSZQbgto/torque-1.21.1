package com.zszq.torque.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zszq.torque.client.bedrock.BedrockBone;
import com.zszq.torque.client.bedrock.BedrockCube;
import com.zszq.torque.client.bedrock.BedrockGeometry;
import com.zszq.torque.client.bedrock.FaceUV;
import com.zszq.torque.client.bedrock.Skeleton;
import com.zszq.torque.client.bedrock.SkeletonPose;
import java.util.Set;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * “关节贴图类”：把 Bedrock 几何体的 cube 按骨骼世界矩阵铺成带贴图的四边形。
 *
 * <p><b>低耦合</b>：只依赖 {@link Skeleton} / {@link SkeletonPose}（骨骼与几何数据）和
 * {@link VertexConsumer} / {@link PoseStack.Pose}（渲染基础类型），不知道实体、贴图路径、
 * RenderType，也不管理光照；调用方把 buffer 绑好、Pose 摆好即可。
 *
 * <p><b>坐标</b>：顶点 = {@code worldMatrix(bone) · (geo 像素 / 16)}，得到 geo 格坐标；
 * 再交给 {@code buffer.addVertex(pose, ...)}，由上层 Pose 负责 geo→model→世界。
 * 法线先用 {@code normalMatrix(bone)} 变换，再交给 {@code pose} 的法线矩阵。
 *
 * <p><b>UV</b>：
 * <ul>
 *   <li>box uv（{@code "uv":[u,v]}）按原版 {@code ModelPart.Cube} 的布局（与 1.21.1 完全一致）；</li>
 *   <li>per-face uv 用 {@code uv} + {@code uv_size}（像素，负值翻转），缺省面回退 box uv；</li>
 *   <li>{@code mirror} 与原版一致：镜像 U、反转顶点顺序、法线 X 取反。</li>
 * </ul>
 *
 * <p>{@code inflate} 把盒子六面各向外扩；cube {@code rotation} 绕 cube 中心按 ZYX 旋转。
 */
public final class SkeletonMeshRenderer {
    /** 1 像素 = 1/16 格 */
    private static final float PIXEL = 1.0F / 16.0F;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** 六个面的顶点顺序表（与原版 {@code ModelPart.Cube} 一致；下标对应 v0..v7）。 */
    private static final int[][] FACE_VERTICES = {
            // 顺序固定为 DOWN, UP, WEST, NORTH, EAST, SOUTH，与 DIRECTIONS 对应
            {4, 3, 7, 0},
            {1, 2, 6, 5},
            {7, 3, 6, 2},
            {0, 7, 2, 1},
            {4, 0, 1, 5},
            {3, 4, 5, 6}
    };

    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH
    };

    /** {@code mirror} 时的顶点顺序：把 {@link #FACE_VERTICES} 整体反转（uv 序列由调用处同步反转）。 */
    private static final int[][] MIRROR_FACE_VERTICES = new int[FACE_VERTICES.length][];

    static {
        for (int face = 0; face < FACE_VERTICES.length; face++) {
            int[] forward = FACE_VERTICES[face];
            MIRROR_FACE_VERTICES[face] = new int[]{forward[3], forward[2], forward[1], forward[0]};
        }
    }

    /** per-face uv 的键，与 {@link #DIRECTIONS} 一一对应。 */
    private static final String[] FACE_NAMES = {"down", "up", "west", "north", "east", "south"};

    private SkeletonMeshRenderer() {
    }

    /**
     * 画整具骨架。
     *
     * @param buffer     已经绑定 RenderType 的顶点缓冲
     * @param pose       调用方 PoseStack 的当前 Pose（内含 geo→model 换算）
     * @param skeleton   几何体骨骼
     * @param skelPose   {@code finish()} 之后的姿态
     * @param hiddenBones 这些骨骼的 cube 不画（手持物品占位骨骼），可为 null
     */
    public static void render(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Skeleton skeleton,
            SkeletonPose skelPose,
            int packedLight,
            int packedOverlay,
            int color,
            Set<String> hiddenBones) {
        BedrockGeometry geometry = skeleton.geometry();
        float textureWidth = geometry.textureWidth();
        float textureHeight = geometry.textureHeight();

        for (int boneIndex = 0; boneIndex < skeleton.boneCount(); boneIndex++) {
            BedrockBone bone = skeleton.bone(boneIndex);
            if (bone.cubes().isEmpty()) {
                continue;
            }

            if (hiddenBones != null && hiddenBones.contains(bone.name())) {
                continue;
            }

            Matrix4f boneMatrix = skelPose.worldMatrix(boneIndex);
            Matrix3f boneNormalMatrix = skelPose.normalMatrix(boneIndex, null);

            for (BedrockCube cube : bone.cubes()) {
                renderCube(buffer, pose, boneMatrix, boneNormalMatrix, cube, textureWidth, textureHeight, packedLight, packedOverlay, color);
            }
        }
    }

    /** 一个 cube：先算出 8 个角点（含 inflate / mirror / rotation），再逐面发四边形。 */
    private static void renderCube(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            Matrix4f boneMatrix,
            Matrix3f boneNormalMatrix,
            BedrockCube cube,
            float textureWidth,
            float textureHeight,
            int packedLight,
            int packedOverlay,
            int color) {
        float[] origin = cube.origin();
        float[] size = cube.size();
        float inflate = cube.inflate();

        float minX = origin[0] - inflate;
        float minY = origin[1] - inflate;
        float minZ = origin[2] - inflate;
        float maxX = origin[0] + size[0] + inflate;
        float maxY = origin[1] + size[1] + inflate;
        float maxZ = origin[2] + size[2] + inflate;

        boolean mirror = cube.mirror();
        if (mirror) {
            // 与原版一致：先沿 X 镜像几何体（盒子对称，等价于换 min/max），后续 UV / 顶点顺序再处理
            float swap = minX;
            minX = maxX;
            maxX = swap;
        }

        float[] rotation = cube.rotation();
        boolean rotated = rotation[0] != 0.0F || rotation[1] != 0.0F || rotation[2] != 0.0F;
        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        float centerZ = (minZ + maxZ) * 0.5F;

        // v0..v7，顺序与原版 Cube 构造函数一致
        float[][] corners = {
                {minX, minY, minZ}, // v0
                {maxX, minY, minZ}, // v1
                {maxX, maxY, minZ}, // v2
                {minX, maxY, minZ}, // v3
                {minX, minY, maxZ}, // v4
                {maxX, minY, maxZ}, // v5
                {maxX, maxY, maxZ}, // v6
                {minX, maxY, maxZ}  // v7
        };

        Vector3f positions = new Vector3f();
        for (float[] corner : corners) {
            if (rotated) {
                rotateAroundCenter(corner, centerX, centerY, centerZ, rotation);
            }
        }

        Matrix3f cubeNormalMatrix = new Matrix3f(boneNormalMatrix);

        for (int face = 0; face < DIRECTIONS.length; face++) {
            Direction direction = DIRECTIONS[face];

            FaceUV faceUV = cube.faceUVs().get(FACE_NAMES[face]);
            if (faceUV == null && !cube.hasBoxUv()) {
                continue;
            }

            float u1;
            float v1;
            float u2;
            float v2;
            if (faceUV != null) {
                u1 = faceUV.u();
                v1 = faceUV.v();
                u2 = faceUV.u() + faceUV.width();
                v2 = faceUV.v() + faceUV.height();
            } else {
                float[] box = boxUv(cube.uv(), size, face);
                u1 = box[0];
                v1 = box[1];
                u2 = box[2];
                v2 = box[3];
            }

            int[] order = FACE_VERTICES[face];
            // 与原版 Polygon 相同的 uv 映射：顶点 0 → (u2,v1)，1 → (u1,v1)，2 → (u1,v2)，3 → (u2,v2)
            float[] us = {u2, u1, u1, u2};
            float[] vs = {v1, v1, v2, v2};

            Vector3f cubeNormal = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
            if (mirror) {
                // 与原版 Polygon 一致：把顶点顺序整个反转（位置 + uv 一起），法线 X 取反
                order = MIRROR_FACE_VERTICES[face];
                vs = new float[]{v2, v2, v1, v1};
                cubeNormal.x *= -1.0F;
            }

            if (rotated) {
                rotationMatrix(rotation).transform(cubeNormal);
            }
            cubeNormal.normalize();
            boneNormalMatrix.transform(cubeNormal).normalize();

            for (int vertex = 0; vertex < 4; vertex++) {
                int index = order[vertex];
                float x = corners[index][0];
                float y = corners[index][1];
                float z = corners[index][2];

                positions.set(x * PIXEL, y * PIXEL, z * PIXEL);
                boneMatrix.transformPosition(positions);

                buffer.addVertex(pose, positions.x(), positions.y(), positions.z())
                        .setColor(color)
                        .setUv(us[vertex] / textureWidth, vs[vertex] / textureHeight)
                        .setOverlay(packedOverlay)
                        .setLight(packedLight)
                        .setNormal(pose, cubeNormal.x(), cubeNormal.y(), cubeNormal.z());
            }
        }
    }

    /**
     * box uv：与原版 {@code ModelPart.Cube} 完全一致的四面展开布局。
     * 返回 {@code [u1, v1, u2, v2]}（像素，左上角 / 右下角）。
     */
    private static float[] boxUv(float[] uv, float[] size, int face) {
        float u = uv[0];
        float v = uv[1];
        float w = size[0];
        float h = size[1];
        float d = size[2];

        return switch (face) {
            case 0 -> new float[]{u + d, v, u + d + w, v + d};          // DOWN
            case 1 -> new float[]{u + d + w, v + d, u + d + w + w, v};  // UP（V 反向）
            case 2 -> new float[]{u, v + d, u + d, v + d + h};          // WEST
            case 3 -> new float[]{u + d, v + d, u + d + w, v + d + h};  // NORTH
            case 4 -> new float[]{u + d + w, v + d, u + d + w + d, v + d + h}; // EAST
            default -> new float[]{u + d + w + d, v + d, u + d + w + d + w, v + d + h}; // SOUTH
        };
    }

    /** 绕 cube 中心按 ZYX 旋转（与原版 {@code ModelPart#rotationZYX} 同序）。 */
    private static void rotateAroundCenter(float[] point, float centerX, float centerY, float centerZ, float[] rotation) {
        Vector3f relative = new Vector3f(point[0] - centerX, point[1] - centerY, point[2] - centerZ);
        Matrix3f matrix = rotationMatrix(rotation);
        matrix.transform(relative);
        point[0] = relative.x + centerX;
        point[1] = relative.y + centerY;
        point[2] = relative.z + centerZ;
    }

    private static Matrix3f rotationMatrix(float[] rotation) {
        return new Matrix3f().rotationZYX(
                rotation[2] * DEG_TO_RAD,
                rotation[1] * DEG_TO_RAD,
                rotation[0] * DEG_TO_RAD);
    }
}
