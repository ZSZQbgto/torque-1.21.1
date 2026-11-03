package com.zszq.torque.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * bedrock 包内部的 JSON 读值小工具。
 *
 * <p>Gson 的 {@code getAsXxx()} 在类型不符时会抛 {@link RuntimeException}（{@code IllegalStateException} /
 * {@code NumberFormatException}）。资源包里的 json 是外部输入，这里全部做成"读不出来就用缺省值"，
 * 保证单个字段写错不会让整个文件解析失败。
 */
final class BedrockJson {
    private BedrockJson() {
    }

    static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static JsonArray array(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    static String string(JsonElement element, String fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }

        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    static boolean bool(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }

        try {
            return element.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    static float number(JsonElement element, float fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }

        try {
            return element.getAsFloat();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    static int integer(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }

        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    /**
     * 把一个数字数组读进 {@code out}：最多写 {@code out.length} 个分量，缺的分量保持原值；
     * 如果给的是单个数字（scale 常见写法），三个分量都填成它。
     */
    static void vector(JsonElement element, float[] out) {
        if (element == null) {
            return;
        }

        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < out.length && i < array.size(); i++) {
                out[i] = number(array.get(i), out[i]);
            }
            return;
        }

        if (element.isJsonPrimitive()) {
            float scalar = number(element, 0.0F);
            for (int i = 0; i < out.length; i++) {
                out[i] = scalar;
            }
        }
    }
}
