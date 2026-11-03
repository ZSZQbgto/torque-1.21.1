package com.zszq.torque.client.bedrock;

import java.util.Map;

/**
 * 一个立方体（Bedrock geometry 里 {@code bones[].cubes[]} 的元素），所有长度单位都是 geo 像素。
 *
 * <p>坐标语义见 {@code docs/animation-engine.md} §2.3：{@code origin} 是 geo 空间的绝对坐标
 * （不是相对骨骼 pivot），{@code size} 是三条边的长度，{@code inflate} 让六个面各自向外扩若干像素。
 * {@code rotation}（度）绕 <b>立方体中心</b> 旋转，顺序 ZYX；{@code mirror} 用于原版
 * {@code ModelPart.Cube} 那种左右镜像的 UV 布局。
 *
 * <p>UV 有两种写法：
 * <ul>
 *   <li>box uv：{@code "uv": [u, v]} → {@code uv} 非 null、{@code faceUVs} 为空表；</li>
 *   <li>per-face uv：{@code "uv": {"north": {...}, ...}} → {@code uv} 为 null、{@code faceUVs} 有值。</li>
 * </ul>
 * 两种都可能是 {@code null}（cube 没写 uv），渲染层自行跳过。
 *
 * <p>数组字段是内部共享引用，请勿修改。
 *
 * @param origin  3 个分量，geo 像素，立方体最小角
 * @param size    3 个分量，geo 像素，三条边长度
 * @param inflate 向外膨胀量（像素），没有就是 0
 * @param mirror  是否镜像
 * @param rotation 3 个分量，度，绕立方体中心按 ZYX 旋转；没有就是 {0,0,0}
 * @param uv      box uv 的 [u, v]（像素）；per-face uv 时为 null
 * @param faceUVs 面名 → UV；面名固定为 "north" / "east" / "south" / "west" / "up" / "down"
 */
public record BedrockCube(
        float[] origin,
        float[] size,
        float inflate,
        boolean mirror,
        float[] rotation,
        float[] uv,
        Map<String, FaceUV> faceUVs) {

    public BedrockCube {
        origin = normalize(origin, 3);
        size = normalize(size, 3);
        rotation = normalize(rotation, 3);
        faceUVs = faceUVs == null ? Map.of() : Map.copyOf(faceUVs);
    }

    private static float[] normalize(float[] value, int length) {
        if (value != null && value.length >= length) {
            return value;
        }

        float[] result = new float[length];
        if (value != null) {
            System.arraycopy(value, 0, result, 0, Math.min(value.length, length));
        }

        return result;
    }

    /** 是否是 box uv（{@code uv} 为 [u, v] 而不是 per-face 对象）。 */
    public boolean hasBoxUv() {
        return this.uv != null && this.uv.length >= 2;
    }
}
