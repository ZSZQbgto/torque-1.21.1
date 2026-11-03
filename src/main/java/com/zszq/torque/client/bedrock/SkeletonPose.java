package com.zszq.torque.client.bedrock;

import java.util.Arrays;
import java.util.Objects;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一具骨骼的可复用姿态累加器：每帧 {@link #clear()} → 若干次
 * {@link #addAnimation(BedrockAnimation, float, float)} → {@link #finish()}，然后渲染层读
 * {@link #worldMatrix(int)} / {@link #rotation(int)} 等。
 *
 * <h2>混合规则（{@code docs/animation-engine.md} §4，必须一字不差）</h2>
 *
 * <p>{@code addAnimation} 对每根骨骼：
 * <ul>
 *   <li>rotation 通道有数据：{@code accRot += weight * 采样值}（度）；<br>
 *       没有数据：{@code accRot += weight * 骨骼静止旋转}；</li>
 *   <li>position 通道有数据：{@code accPos += weight * 采样值}（像素）；没有数据：不变；</li>
 *   <li>scale 通道有数据：{@code accScale += weight * 采样值}；没有数据：{@code accScale += weight * 1}。</li>
 * </ul>
 * 最后 {@code totalWeight += weight}。调用方负责让所有 weight 之和 ≤ 1；剩下的
 * {@code 1 - totalWeight} 在 {@link #finish()} 里补成静止姿态。
 *
 * <p>{@code finish()} 内部（全部在 geo 空间，像素转格）：
 * <pre>
 * rot   = accRot + (1 - totalWeight) * restRotation    // 度，ZYX
 * pos   = accPos                                       // 像素（静止位移为 0）
 * scale = accScale + (1 - totalWeight) * 1
 * local = T((pivot + pos) / 16) · Rz·Ry·Rx(rot) · S(scale) · T(-pivot / 16)
 * world(bone) = world(parent) · local
 * </pre>
 *
 * <p><b>零分配</b>：所有数组（每骨骼的 float[3]、{@link Matrix4f}、{@link Matrix3f}、{@link Vector3f}）
 * 都在构造时分配好，每帧只复用，不在渲染热路径上 new 对象。
 *
 * <p><b>内部复用语义</b>：{@link #rotation(int)} / {@link #position(int)} / {@link #scale(int)} /
 * {@link #worldMatrix(int)} / {@link #worldPivot(int, Vector3f)} / {@link #normalMatrix(int, Matrix3f)}
 * 返回的都是内部对象，只在本次 {@code finish()} 之后、下一次 {@code finish()} 之前有效；调用方不要长期保存，
 * 也不要修改。
 */
public final class SkeletonPose {
    /** 1 像素 = 1/16 格。 */
    private static final float PIXELS_TO_BLOCKS = 1.0F / 16.0F;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** 法线矩阵求逆的行列式下限，太接近 0 说明骨骼缩放退化了。 */
    private static final float NORMAL_INVERT_EPSILON = 1.0E-6F;

    private final Skeleton skeleton;
    private final int boneCount;

    /** addAnimation 的累加器。 */
    private final float[][] accumulatedRotation;
    private final float[][] accumulatedPosition;
    private final float[][] accumulatedScale;
    private float totalWeight;

    /** finish() 之后的最终采样结果。 */
    private final float[][] rotation;
    private final float[][] position;
    private final float[][] scale;

    /** finish() 之后的世界矩阵（geo 空间，单位格）。 */
    private final Matrix4f[] worldMatrices;
    private final Matrix3f[] normalMatrices;
    private final Vector3f[] worldPivots;

    /** 计算用的临时对象，全部复用。 */
    private final Matrix4f localMatrix = new Matrix4f();
    private final float[] sampleScratch = new float[3];
    private final int[] parentChain;
    private final boolean[] computed;

    public SkeletonPose(Skeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.boneCount = skeleton.boneCount();

        this.accumulatedRotation = new float[this.boneCount][3];
        this.accumulatedPosition = new float[this.boneCount][3];
        this.accumulatedScale = new float[this.boneCount][3];

        this.rotation = new float[this.boneCount][3];
        this.position = new float[this.boneCount][3];
        this.scale = new float[this.boneCount][3];

        this.worldMatrices = new Matrix4f[this.boneCount];
        this.normalMatrices = new Matrix3f[this.boneCount];
        this.worldPivots = new Vector3f[this.boneCount];
        for (int i = 0; i < this.boneCount; i++) {
            this.worldMatrices[i] = new Matrix4f();
            this.normalMatrices[i] = new Matrix3f();
            this.worldPivots[i] = new Vector3f();
        }

        this.parentChain = new int[this.boneCount];
        this.computed = new boolean[this.boneCount];

        clear();
    }

    public Skeleton skeleton() {
        return this.skeleton;
    }

    /**
     * 清空累加器（旋转 0、位移 0、缩放 0、权重 0）。
     *
     * <p>注意缩放累加器这里填的是 <b>0</b> 而不是 1：静止值 1 由 {@link #finish()} 的
     * {@code (1 - totalWeight) * 1} 补上。如果这里填 1，没加任何动画时 finish 会得到
     * {@code 1 + (1 - 0) * 1 = 2}，角色会被放大一倍。
     */
    public void clear() {
        for (int i = 0; i < this.boneCount; i++) {
            Arrays.fill(this.accumulatedRotation[i], 0.0F);
            Arrays.fill(this.accumulatedPosition[i], 0.0F);
            Arrays.fill(this.accumulatedScale[i], 0.0F);
        }

        this.totalWeight = 0.0F;
    }

    /**
     * 把一条动画在 {@code time} 处的姿态按 {@code weight} 混进累加器。
     *
     * <p>规则见类注释；{@code time} 会先经过 {@link BedrockAnimation#wrap(float)} 折到循环区间内。
     * 动画里没有的骨骼、没有的通道按文档补静止值，不会报错。
     *
     * @param animation 动画；null 时直接忽略（不增加权重）
     * @param time      秒
     * @param weight    混合权重，&le; 0 时忽略
     */
    public void addAnimation(BedrockAnimation animation, float time, float weight) {
        if (animation == null || weight <= 0.0F) {
            return;
        }

        float wrapped = animation.wrap(time);
        boolean loop = animation.loop();

        for (int i = 0; i < this.boneCount; i++) {
            BedrockBone bone = this.skeleton.bone(i);
            BoneTimeline timeline = animation.bone(bone.name());

            // rotation：有通道用采样值，没有通道补骨骼静止旋转（同样按权重累加）
            AnimationChannel rotationChannel = timeline.rotation();
            if (rotationChannel.isEmpty()) {
                float[] rest = bone.rotation();
                this.accumulatedRotation[i][0] += weight * rest[0];
                this.accumulatedRotation[i][1] += weight * rest[1];
                this.accumulatedRotation[i][2] += weight * rest[2];
            } else {
                rotationChannel.sample(wrapped, this.sampleScratch, loop);
                this.accumulatedRotation[i][0] += weight * this.sampleScratch[0];
                this.accumulatedRotation[i][1] += weight * this.sampleScratch[1];
                this.accumulatedRotation[i][2] += weight * this.sampleScratch[2];
            }

            // position：只有通道有数据才累加；静止位移为 0，所以没数据时保持不变
            AnimationChannel positionChannel = timeline.position();
            if (!positionChannel.isEmpty()) {
                positionChannel.sample(wrapped, this.sampleScratch, loop);
                this.accumulatedPosition[i][0] += weight * this.sampleScratch[0];
                this.accumulatedPosition[i][1] += weight * this.sampleScratch[1];
                this.accumulatedPosition[i][2] += weight * this.sampleScratch[2];
            }

            // scale：有通道用采样值，没有通道累加 weight * 1
            AnimationChannel scaleChannel = timeline.scale();
            if (scaleChannel.isEmpty()) {
                this.accumulatedScale[i][0] += weight;
                this.accumulatedScale[i][1] += weight;
                this.accumulatedScale[i][2] += weight;
            } else {
                scaleChannel.sample(wrapped, this.sampleScratch, loop);
                this.accumulatedScale[i][0] += weight * this.sampleScratch[0];
                this.accumulatedScale[i][1] += weight * this.sampleScratch[1];
                this.accumulatedScale[i][2] += weight * this.sampleScratch[2];
            }
        }

        this.totalWeight += weight;
    }

    /**
     * 收尾并重建世界矩阵。每帧必须调用一次（即使没加任何动画，也能得到静止姿态）。
     *
     * <p>{@code restWeight = 1 - totalWeight}：调用方保证所有权重之和 ≤ 1 时它落在
     * {@code [0, 1]}，不会给静止姿态补负值；权重和超过 1 是调用方的错误（文档 §4 已约定）。
     */
    public void finish() {
        float restWeight = 1.0F - this.totalWeight;

        for (int i = 0; i < this.boneCount; i++) {
            BedrockBone bone = this.skeleton.bone(i);
            float[] restRotation = bone.rotation();

            this.rotation[i][0] = this.accumulatedRotation[i][0] + restWeight * restRotation[0];
            this.rotation[i][1] = this.accumulatedRotation[i][1] + restWeight * restRotation[1];
            this.rotation[i][2] = this.accumulatedRotation[i][2] + restWeight * restRotation[2];

            this.position[i][0] = this.accumulatedPosition[i][0];
            this.position[i][1] = this.accumulatedPosition[i][1];
            this.position[i][2] = this.accumulatedPosition[i][2];

            this.scale[i][0] = this.accumulatedScale[i][0] + restWeight;
            this.scale[i][1] = this.accumulatedScale[i][1] + restWeight;
            this.scale[i][2] = this.accumulatedScale[i][2] + restWeight;
        }

        rebuildWorldMatrices();
    }

    /**
     * 按父链拓扑序重建世界矩阵（geometry 里父骨骼不一定排在子骨骼前面，所以不能只按数组顺序算）。
     */
    private void rebuildWorldMatrices() {
        Arrays.fill(this.computed, false);

        for (int i = 0; i < this.boneCount; i++) {
            if (this.computed[i]) {
                continue;
            }

            // 先把从 i 到最近一个已计算骨骼（或虚拟根）的整条链压栈，再从栈顶往下算
            int depth = 0;
            int current = i;
            while (current != -1 && !this.computed[current] && depth < this.parentChain.length) {
                this.parentChain[depth++] = current;
                current = this.skeleton.parentOf(current);
            }

            for (int d = depth - 1; d >= 0; d--) {
                int boneIndex = this.parentChain[d];
                buildLocalMatrix(boneIndex);

                int parent = this.skeleton.parentOf(boneIndex);
                if (parent == -1) {
                    this.worldMatrices[boneIndex].set(this.localMatrix);
                } else {
                    this.worldMatrices[boneIndex].set(this.worldMatrices[parent]).mul(this.localMatrix);
                }

                this.computed[boneIndex] = true;
            }
        }

        for (int i = 0; i < this.boneCount; i++) {
            // 动画后的 pivot 世界位置：world(bone) 作用在 pivot/16 上
            float[] pivot = this.skeleton.pivot(i);
            Vector3f worldPivot = this.worldPivots[i].set(
                    pivot[0] * PIXELS_TO_BLOCKS,
                    pivot[1] * PIXELS_TO_BLOCKS,
                    pivot[2] * PIXELS_TO_BLOCKS);
            this.worldMatrices[i].transformPosition(worldPivot);

            // 法线矩阵 = worldMatrix 左上 3x3 的逆转置；骨骼缩放退化（行列式≈0）时退回原始 3x3
            Matrix3f normal = this.normalMatrices[i];
            normal.set(this.worldMatrices[i]);
            if (Math.abs(normal.determinant()) > NORMAL_INVERT_EPSILON) {
                normal.invert().transpose();
            }
        }
    }

    /**
     * local = T((pivot + pos) / 16) · Rz(rz) · Ry(ry) · Rx(rx) · S(scale) · T(-pivot / 16)
     *
     * <p>JOML 的 translate/rotate/scale 都是右乘（post-multiply），按顺序串起来正好等于上面这个矩阵乘积。
     */
    private void buildLocalMatrix(int boneIndex) {
        BedrockBone bone = this.skeleton.bone(boneIndex);
        float[] pivot = bone.pivot();
        float[] pos = this.position[boneIndex];
        float[] rot = this.rotation[boneIndex];
        float[] scl = this.scale[boneIndex];

        float pivotX = pivot[0] * PIXELS_TO_BLOCKS;
        float pivotY = pivot[1] * PIXELS_TO_BLOCKS;
        float pivotZ = pivot[2] * PIXELS_TO_BLOCKS;

        this.localMatrix.identity()
                .translate(pivotX + pos[0] * PIXELS_TO_BLOCKS,
                        pivotY + pos[1] * PIXELS_TO_BLOCKS,
                        pivotZ + pos[2] * PIXELS_TO_BLOCKS)
                .rotateZ(rot[2] * DEG_TO_RAD)
                .rotateY(rot[1] * DEG_TO_RAD)
                .rotateX(rot[0] * DEG_TO_RAD)
                .scale(scl[0], scl[1], scl[2])
                .translate(-pivotX, -pivotY, -pivotZ);
    }

    /** 采样后的旋转（度，geo 轴，顺序 ZYX），只在 {@link #finish()} 后有效，内部数组不要改。 */
    public float[] rotation(int boneIndex) {
        return this.rotation[boneIndex];
    }

    /** 采样后的位移（geo 像素），只在 {@link #finish()} 后有效，内部数组不要改。 */
    public float[] position(int boneIndex) {
        return this.position[boneIndex];
    }

    /** 采样后的缩放（倍率），只在 {@link #finish()} 后有效，内部数组不要改。 */
    public float[] scale(int boneIndex) {
        return this.scale[boneIndex];
    }

    /** 骨骼世界矩阵（geo 空间，单位：格），返回内部对象，下次 {@code finish()} 会被覆盖。 */
    public Matrix4f worldMatrix(int boneIndex) {
        return this.worldMatrices[boneIndex];
    }

    /**
     * 动画后的 pivot 世界位置（geo 空间，单位：格）。
     *
     * <p>返回内部复用的 {@link Vector3f}；{@code dest} 非 null 时顺便把结果拷进去（方便调用方自己保存），
     * 但返回值始终是内部对象。
     */
    public Vector3f worldPivot(int boneIndex, Vector3f dest) {
        Vector3f internal = this.worldPivots[boneIndex];
        if (dest != null) {
            dest.set(internal);
        }

        return internal;
    }

    /**
     * {@link #worldMatrix(int)} 的法线矩阵（左上 3x3 的逆转置），用于变换法线/单位方向向量。
     *
     * <p>返回内部复用的 {@link Matrix3f}；{@code dest} 非 null 时顺便把结果拷进去，返回值始终是内部对象。
     */
    public Matrix3f normalMatrix(int boneIndex, Matrix3f dest) {
        Matrix3f internal = this.normalMatrices[boneIndex];
        if (dest != null) {
            dest.set(internal);
        }

        return internal;
    }
}
