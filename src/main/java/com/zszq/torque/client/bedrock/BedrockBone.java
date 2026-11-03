package com.zszq.torque.client.bedrock;

import java.util.List;
import java.util.Objects;

/**
 * 一根骨骼（Bedrock geometry 的 {@code bones[]} 元素）。
 *
 * <p>{@code pivot} 与 {@code rotation} 都在 geo 空间：pivot 是绝对像素坐标（§2.3），rotation 是静止姿态的
 * 欧拉角（度，顺序 ZYX，见 §2.5）。骨骼的动画会在这个静止姿态之上叠加，具体见
 * {@link SkeletonPose#addAnimation(BedrockAnimation, float, float)}。
 *
 * <p>{@link #pivot()} / {@link #rotation()} 返回的是内部数组引用（{@link Skeleton#pivot(int)} 也直接引用它），
 * 调用方不要修改。
 */
public final class BedrockBone {
    private final String name;
    private final String parent;
    private final float[] pivot;
    private final float[] rotation;
    private final List<BedrockCube> cubes;

    /**
     * @param name     骨骼名（必须唯一）
     * @param parent   父骨骼名；null 表示挂在虚拟根下
     * @param pivot    3 个分量，geo 像素
     * @param rotation 3 个分量，度，ZYX；null 视为 {0,0,0}
     * @param cubes    这根骨骼上的立方体；null 视为空表
     */
    public BedrockBone(String name, String parent, float[] pivot, float[] rotation, List<BedrockCube> cubes) {
        this.name = Objects.requireNonNull(name, "name");
        this.parent = parent;
        this.pivot = vector3(pivot);
        this.rotation = vector3(rotation);
        this.cubes = cubes == null ? List.of() : List.copyOf(cubes);
    }

    private static float[] vector3(float[] value) {
        if (value == null) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }

        if (value.length < 3) {
            throw new IllegalArgumentException("expected 3 components, got " + value.length);
        }

        return value;
    }

    public String name() {
        return this.name;
    }

    /** 父骨骼名；null 表示挂在虚拟根下。 */
    public String parent() {
        return this.parent;
    }

    /** geo 像素，直接引用内部数组。 */
    public float[] pivot() {
        return this.pivot;
    }

    /** 静止姿态欧拉角（度，ZYX），直接引用内部数组。 */
    public float[] rotation() {
        return this.rotation;
    }

    public List<BedrockCube> cubes() {
        return this.cubes;
    }

    @Override
    public String toString() {
        return "BedrockBone[" + this.name + (this.parent == null ? "" : ", parent=" + this.parent) + "]";
    }
}
