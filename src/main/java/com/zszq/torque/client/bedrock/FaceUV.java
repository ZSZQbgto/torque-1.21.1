package com.zszq.torque.client.bedrock;

/**
 * 几何体里一个面的 UV 矩形，单位是贴图像素。
 *
 * <p>{@code width} / {@code height} 允许为负数：Bedrock 的 per-face {@code uv_size} 用负值表示该面
 * 在 U / V 方向翻转（Blockbench 导出常见，例如
 * {@code "up": {"uv": [56, 21], "uv_size": [-10, -1]}}）。渲染层按
 * {@code u} 到 {@code u + width}、{@code v} 到 {@code v + height} 取矩形即可，不需要预先翻正。
 *
 * @param u      UV 左上角 U（像素）
 * @param v      UV 左上角 V（像素）
 * @param width  UV 宽度（像素，可为负 = U 翻转）
 * @param height UV 高度（像素，可为负 = V 翻转）
 */
public record FaceUV(float u, float v, float width, float height) {
    /** U 方向是否翻转（即 {@code width < 0}）。 */
    public boolean flippedU() {
        return this.width < 0.0F;
    }

    /** V 方向是否翻转（即 {@code height < 0}）。 */
    public boolean flippedV() {
        return this.height < 0.0F;
    }
}
