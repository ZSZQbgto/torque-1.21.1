package com.zszq.torque.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zszq.torque.Torque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一条 Bedrock 动画（{@code animations} 对象下的一项）。
 *
 * <p>结构只有四样：名字、是否循环、总长（秒）、以及每根骨骼上的三条通道。骨骼名直接沿用
 * Blockbench 的组名，所以完全不认识具体是哪些骨骼——geometry 里有就生效，没有就跳过。
 *
 * <p>坐标/单位见 {@code docs/animation-engine.md} §2.5：rotation 是度（骨骼局部空间，ZYX），
 * position 是 geo 像素，scale 是倍率；时间单位是秒，{@code loop = true} 时按 {@link #length()} 取模。
 */
public final class BedrockAnimation {
    private final String name;
    private final boolean loop;
    private final float length;
    private final Map<String, BoneTimeline> bones;

    /**
     * @param name   动画名（json 里 {@code animations} 的键）
     * @param loop   是否循环
     * @param length 总长（秒）；{@code <= 0} 时退回最后一个关键帧时间
     * @param bones  骨骼名 → 三条通道
     */
    public BedrockAnimation(String name, boolean loop, float length, Map<String, BoneTimeline> bones) {
        this.name = Objects.requireNonNull(name, "name");
        this.loop = loop;
        this.bones = bones == null ? Map.of() : Map.copyOf(bones);
        this.length = length > 0.0F ? length : lastTime(this.bones);
    }

    public String name() {
        return this.name;
    }

    public boolean loop() {
        return this.loop;
    }

    /** 总长（秒）；缺 {@code animation_length} 时取最后关键帧时间。 */
    public float length() {
        return this.length;
    }

    /** 这根骨骼的通道，没有返回 {@link BoneTimeline#EMPTY}。 */
    public BoneTimeline bone(String name) {
        if (name == null) {
            return BoneTimeline.EMPTY;
        }

        return this.bones.getOrDefault(name, BoneTimeline.EMPTY);
    }

    /**
     * 把"从动画开始算起的时间"折成采样用的时间：循环动画绕回 {@code [0, length)}，
     * 不循环的夹在 {@code [0, length]}。
     */
    public float wrap(float time) {
        if (this.length <= 0.0F) {
            return 0.0F;
        }

        if (!this.loop) {
            return Math.max(0.0F, Math.min(time, this.length));
        }

        float wrapped = time % this.length;
        return wrapped < 0.0F ? wrapped + this.length : wrapped;
    }

    /**
     * 解析一个动画文件里的所有动画，返回 动画名 → 动画。
     *
     * <p>单个动画解析失败只 {@code error} 并跳过，不影响同文件里的其它动画。
     */
    public static Map<String, BedrockAnimation> parseAll(JsonObject root) {
        JsonObject animations = BedrockJson.object(root == null ? null : root.get("animations"));
        if (animations == null) {
            return Map.of();
        }

        Map<String, BedrockAnimation> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                Torque.LOGGER.warn("Skipping non-object animation entry {}", entry.getKey());
                continue;
            }

            try {
                result.put(entry.getKey(), parse(entry.getKey(), entry.getValue().getAsJsonObject()));
            } catch (RuntimeException exception) {
                Torque.LOGGER.error("Failed to parse bedrock animation {}", entry.getKey(), exception);
            }
        }

        return Map.copyOf(result);
    }

    private static BedrockAnimation parse(String name, JsonObject json) {
        boolean loop = BedrockJson.bool(json.get("loop"), false);

        Map<String, BoneTimeline> bones = new HashMap<>();
        JsonObject bonesJson = BedrockJson.object(json.get("bones"));
        if (bonesJson != null) {
            for (Map.Entry<String, JsonElement> entry : bonesJson.entrySet()) {
                JsonObject boneJson = BedrockJson.object(entry.getValue());
                if (boneJson == null) {
                    continue;
                }

                bones.put(entry.getKey(), new BoneTimeline(
                        parseChannel(boneJson.get("rotation")),
                        parseChannel(boneJson.get("position")),
                        parseChannel(boneJson.get("scale"))));
            }
        }

        float length = BedrockJson.number(json.get("animation_length"), 0.0F);
        return new BedrockAnimation(name, loop, length, bones);
    }

    /**
     * 解析一条通道：键是时间（秒），值可以是数组、单个数字（scale 常见），或
     * {@code {"post": ..., "lerp_mode": ...}} 对象。
     */
    private static AnimationChannel parseChannel(JsonElement element) {
        JsonObject json = BedrockJson.object(element);
        if (json == null) {
            return AnimationChannel.EMPTY;
        }

        List<Keyframe> frames = new ArrayList<>(json.size());
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            float time;
            try {
                time = Float.parseFloat(entry.getKey().trim());
            } catch (NumberFormatException exception) {
                // 不是时间键，忽略
                continue;
            }

            JsonElement valueElement = entry.getValue();
            float[] value = new float[3];
            AnimationChannel.LerpMode mode = AnimationChannel.LerpMode.LINEAR;

            if (valueElement != null && valueElement.isJsonObject()) {
                JsonObject frame = valueElement.getAsJsonObject();
                // 有 post 取 post（离开这一帧的值），没有就退回 pre
                JsonElement post = frame.has("post") ? frame.get("post") : frame.get("pre");
                if (post == null) {
                    continue;
                }

                BedrockJson.vector(post, value);
                mode = parseLerpMode(BedrockJson.string(frame.get("lerp_mode"), null));
            } else if (valueElement != null) {
                BedrockJson.vector(valueElement, value);
            } else {
                continue;
            }

            frames.add(new Keyframe(time, value, mode));
        }

        if (frames.isEmpty()) {
            return AnimationChannel.EMPTY;
        }

        // json 的键顺序不保证按时间排，稳定排序一遍（时间相同的帧保持原顺序）
        frames.sort(Comparator.comparingDouble(Keyframe::time));

        float[] times = new float[frames.size()];
        float[][] values = new float[frames.size()][];
        AnimationChannel.LerpMode[] modes = new AnimationChannel.LerpMode[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            Keyframe frame = frames.get(i);
            times[i] = frame.time();
            values[i] = frame.value();
            modes[i] = frame.mode();
        }

        return new AnimationChannel(times, values, modes);
    }

    private static AnimationChannel.LerpMode parseLerpMode(String name) {
        if (name == null) {
            return AnimationChannel.LerpMode.LINEAR;
        }

        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "catmullrom" -> AnimationChannel.LerpMode.CATMULLROM;
            case "step" -> AnimationChannel.LerpMode.STEP;
            default -> AnimationChannel.LerpMode.LINEAR;
        };
    }

    private static float lastTime(Map<String, BoneTimeline> bones) {
        float last = 0.0F;
        for (BoneTimeline timeline : bones.values()) {
            last = Math.max(last, timeline.rotation().lastTime());
            last = Math.max(last, timeline.position().lastTime());
            last = Math.max(last, timeline.scale().lastTime());
        }

        return last;
    }

    /** 解析过程中的临时结构：一个关键帧。 */
    private record Keyframe(float time, float[] value, AnimationChannel.LerpMode mode) {
    }

    @Override
    public String toString() {
        return "BedrockAnimation[" + this.name + ", loop=" + this.loop + ", length=" + this.length
                + ", bones=" + this.bones.size() + "]";
    }
}
