package com.zszq.torque.client.bedrock;

/**
 * 一条动画通道（rotation / position / scale），保存按时间排好序的关键帧并在任意时刻采样。
 *
 * <p>Bedrock 动画里每个关键帧长这样：{@code "0.0": [0, -2, 1.5]}，或带插值模式：
 * {@code "0.0": {"post": [0, -2, 1.5], "lerp_mode": "catmullrom"}}。这里只取 {@code post}
 * （离开这一帧用的值）；{@code pre} 只有同一时间点需要不同的到达 / 离开值时才出现，按文档忽略。
 *
 * <p>插值方式：
 * <ul>
 *   <li>{@link LerpMode#LINEAR}：默认，线性插值；</li>
 *   <li>{@link LerpMode#CATMULLROM}：用相邻四点（前、起点、终点、后）算切线；</li>
 *   <li>{@link LerpMode#STEP}：保持上一帧的值，直到下一帧。</li>
 * </ul>
 * 与 Bedrock 一致，{@code lerp_mode} 挂在"离开这一帧"的关键帧上，所以 {@code modes[i]} 描述的是
 * 第 i 帧到第 i+1 帧的插值方式。
 *
 * <p>采样时超出首尾就 clamp 到端点；循环由调用方先把时间折回 {@code [0, length)} 再传给
 * {@link #sample(float, float[], boolean)}，{@code loop} 参数只影响 Catmull-Rom 取邻帧的方式。
 */
public final class AnimationChannel {
    /** 空通道：没有关键帧，{@link #sample} 永远输出 0。 */
    public static final AnimationChannel EMPTY = new AnimationChannel(new float[0], new float[0][], new LerpMode[0]);

    /** 关键帧之间的过渡方式。 */
    public enum LerpMode {
        LINEAR,
        CATMULLROM,
        STEP
    }

    private static final float EPSILON = 1.0E-6F;

    private final float[] times;
    private final float[][] values;
    /** {@code modes[i]} 是第 i 帧到第 i + 1 帧的插值方式。 */
    private final LerpMode[] modes;

    AnimationChannel(float[] times, float[][] values, LerpMode[] modes) {
        this.times = times;
        this.values = values;
        this.modes = modes;
    }

    public boolean isEmpty() {
        return this.times.length == 0;
    }

    /** 最晚的关键帧时间（秒）；空通道返回 0，用来补 {@code animation_length} 缺失的情况。 */
    public float lastTime() {
        return this.times.length == 0 ? 0.0F : this.times[this.times.length - 1];
    }

    /**
     * 在 {@code time} 处采样，结果写进 {@code out}（长度 3）。
     *
     * @param time 已经由调用方处理过循环（见 {@link BedrockAnimation#wrap(float)}）的时间，单位秒
     * @param out  输出数组，长度 3
     * @param loop 动画是否循环；只影响 Catmull-Rom 相邻四点的取法
     */
    public void sample(float time, float[] out, boolean loop) {
        int count = this.times.length;
        if (count == 0) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            return;
        }

        if (time <= this.times[0]) {
            copy(this.values[0], out);
            return;
        }

        if (time >= this.times[count - 1]) {
            copy(this.values[count - 1], out);
            return;
        }

        int index = 0;
        while (index < count - 2 && this.times[index + 1] <= time) {
            index++;
        }

        float span = this.times[index + 1] - this.times[index];
        float progress = span <= EPSILON ? 0.0F : (time - this.times[index]) / span;

        switch (this.modes[index]) {
            case STEP -> copy(this.values[index], out);
            case CATMULLROM -> this.catmullrom(index, progress, out, loop);
            default -> this.linear(index, progress, out);
        }
    }

    private void linear(int index, float t, float[] out) {
        float[] from = this.values[index];
        float[] to = this.values[index + 1];
        for (int i = 0; i < 3; i++) {
            out[i] = from[i] + (to[i] - from[i]) * t;
        }
    }

    /**
     * Catmull-Rom：用前后各一帧当切线依据，公式是标准的四点样条。
     *
     * <p>循环动画的首尾要互相环绕着取邻帧（{@code t = 0} 的前一帧取倒数第二帧、最后一帧的后一帧取第二帧），
     * 否则循环接缝处速度会突然停一下；不循环的动画才把端点复制一份。
     */
    private void catmullrom(int index, float t, float[] out, boolean loop) {
        int count = this.times.length;
        int previous = index - 1;
        int next = index + 2;

        if (loop) {
            if (previous < 0) {
                previous = Math.max(0, count - 2);
            }
            if (next >= count) {
                next = Math.min(count - 1, 1);
            }
        } else {
            previous = Math.max(0, previous);
            next = Math.min(count - 1, next);
        }

        float[] p0 = this.values[previous];
        float[] p1 = this.values[index];
        float[] p2 = this.values[index + 1];
        float[] p3 = this.values[next];
        float t2 = t * t;
        float t3 = t2 * t;

        for (int i = 0; i < 3; i++) {
            out[i] = 0.5F * (2.0F * p1[i]
                    + (p2[i] - p0[i]) * t
                    + (2.0F * p0[i] - 5.0F * p1[i] + 4.0F * p2[i] - p3[i]) * t2
                    + (3.0F * p1[i] - p0[i] - 3.0F * p2[i] + p3[i]) * t3);
        }
    }

    private static void copy(float[] from, float[] to) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }
}
