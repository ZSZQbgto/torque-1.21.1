package com.zszq.torque.client.bedrock;

import com.zszq.torque.Torque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 由 {@link BedrockGeometry} 建出来的骨骼树：把骨骼名字、父子关系预先解析成数组下标，供每帧的姿态计算使用。
 *
 * <p>约定见 {@code docs/animation-engine.md} §4：
 * <ul>
 *   <li>骨骼顺序沿用 geometry 里的 {@code bones} 顺序，{@link #bone(int)} 的下标就是这个顺序；</li>
 *   <li>{@code parent} 不存在或为 null 的骨骼挂到虚拟根（{@link #parentOf(int)} 返回 -1）；</li>
 *   <li>名字重复时后一个胜出并 log warn；</li>
 *   <li>父链成环时把出问题的骨骼挂回虚拟根并 log warn，保证 {@link SkeletonPose#finish()} 不会死循环。</li>
 * </ul>
 *
 * <p>{@link #pivot(int)} 直接返回 geometry 里的数组引用，不做拷贝。
 */
public final class Skeleton {
    private final BedrockGeometry geometry;
    private final BedrockBone[] bones;
    private final int[] parents;
    private final Map<String, Integer> indicesByName;

    private Skeleton(BedrockGeometry geometry) {
        this.geometry = geometry;
        this.bones = geometry.bones().toArray(new BedrockBone[0]);

        int boneCount = this.bones.length;
        Map<String, Integer> indices = new HashMap<>(Math.max(16, boneCount * 2));
        for (int i = 0; i < boneCount; i++) {
            Integer previous = indices.put(this.bones[i].name(), i);
            if (previous != null) {
                Torque.LOGGER.warn("Duplicate bone name '{}' in geometry {}, bone #{} overrides bone #{}",
                        this.bones[i].name(), geometry.identifier(), i, previous);
            }
        }

        this.indicesByName = Map.copyOf(indices);

        this.parents = new int[boneCount];
        for (int i = 0; i < boneCount; i++) {
            String parentName = this.bones[i].parent();
            if (parentName == null) {
                this.parents[i] = -1;
                continue;
            }

            Integer parentIndex = indices.get(parentName);
            if (parentIndex == null) {
                Torque.LOGGER.warn("Bone '{}' in geometry {} references missing parent '{}', attaching to root",
                        this.bones[i].name(), geometry.identifier(), parentName);
                this.parents[i] = -1;
            } else {
                this.parents[i] = parentIndex;
            }
        }

        breakParentCycles(geometry.identifier());
    }

    /**
     * 父链成环（例如 a → b → a）会让世界矩阵拓扑排序死循环。这里逐个骨骼向上走，
     * 发现回到自己或超过骨骼数就把该骨骼挂回虚拟根。
     */
    private void breakParentCycles(String geometryIdentifier) {
        int boneCount = this.bones.length;
        for (int i = 0; i < boneCount; i++) {
            int steps = 0;
            int current = this.parents[i];
            while (current != -1) {
                if (current == i || ++steps > boneCount) {
                    Torque.LOGGER.warn("Bone '{}' in geometry {} has a cyclic parent chain, attaching to root",
                            this.bones[i].name(), geometryIdentifier);
                    this.parents[i] = -1;
                    break;
                }

                current = this.parents[current];
            }
        }
    }

    /** 由几何体建骨骼树；几何体为 null 抛 NPE。 */
    public static Skeleton of(BedrockGeometry geometry) {
        return new Skeleton(Objects.requireNonNull(geometry, "geometry"));
    }

    public BedrockGeometry geometry() {
        return this.geometry;
    }

    public int boneCount() {
        return this.bones.length;
    }

    /** 按名字取骨骼下标，没有返回 -1。 */
    public int indexOf(String name) {
        if (name == null) {
            return -1;
        }

        Integer index = this.indicesByName.get(name);
        return index == null ? -1 : index;
    }

    public BedrockBone bone(int index) {
        return this.bones[index];
    }

    /** 父骨骼下标；-1 表示虚拟根。 */
    public int parentOf(int index) {
        return this.parents[index];
    }

    /** geo 像素的 pivot，直接引用 geometry 里的数组。 */
    public float[] pivot(int index) {
        return this.bones[index].pivot();
    }
}
