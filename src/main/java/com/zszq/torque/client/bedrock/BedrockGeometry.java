package com.zszq.torque.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zszq.torque.Torque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 一份 Bedrock 几何体（{@code *.geo.json} 里 {@code minecraft:geometry} 的一个元素）。
 *
 * <p>这是纯数据类：解析后不再变化。骨骼顺序就是 json 里的顺序，{@link Skeleton} 按这个顺序建数组。
 * 坐标语义见 {@code docs/animation-engine.md} §2.3：1 像素 = 1/16 格、+Y 向上、脚在 y = 0。
 *
 * <p>解析要求（§4）：{@code format_version 1.12.0}；{@code minecraft:geometry} 可能是数组也可能是单对象；
 * {@code uv} 支持 {@code [u, v]} 与 per-face 对象两种；{@code description.identifier}、
 * {@code texture_width/height} 必读；未知字段忽略。
 */
public final class BedrockGeometry {
    /** per-face uv 的六个合法面名。 */
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    private final String identifier;
    private final int textureWidth;
    private final int textureHeight;
    private final List<BedrockBone> bones;
    private final Map<String, BedrockBone> bonesByName;

    /**
     * @param identifier    形如 {@code geometry.default_player}
     * @param textureWidth  贴图宽（像素），非正数时退回 64
     * @param textureHeight 贴图高（像素），非正数时退回 64
     * @param bones         骨骼列表，null 视为空表
     */
    public BedrockGeometry(String identifier, int textureWidth, int textureHeight, List<BedrockBone> bones) {
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.textureWidth = textureWidth > 0 ? textureWidth : 64;
        this.textureHeight = textureHeight > 0 ? textureHeight : 64;
        this.bones = bones == null ? List.of() : List.copyOf(bones);

        Map<String, BedrockBone> byName = new HashMap<>();
        for (BedrockBone bone : this.bones) {
            // 名字重复时后一个胜出（与 Skeleton 的约定一致）；这里不刷日志，Skeleton 建树时会 warn 一次
            byName.put(bone.name(), bone);
        }

        this.bonesByName = Map.copyOf(byName);
    }

    public String identifier() {
        return this.identifier;
    }

    public int textureWidth() {
        return this.textureWidth;
    }

    public int textureHeight() {
        return this.textureHeight;
    }

    public List<BedrockBone> bones() {
        return this.bones;
    }

    /** 按名字取骨骼，没有返回 null。 */
    public BedrockBone bone(String name) {
        if (name == null) {
            return null;
        }

        return this.bonesByName.get(name);
    }

    /**
     * 解析一个几何体 json 文件，返回文件里包含的所有几何体（{@code minecraft:geometry} 数组可以放多个）。
     *
     * <p>单个几何体解析失败只 {@code error} 并跳过，不影响同一个文件里的其它几何体。
     */
    public static List<BedrockGeometry> parseAll(JsonObject root) {
        List<BedrockGeometry> result = new ArrayList<>();
        if (root == null) {
            return result;
        }

        String formatVersion = BedrockJson.string(root.get("format_version"), null);
        if (!"1.12.0".equals(formatVersion)) {
            // 其它版本的字段差异很小，照常尝试解析，只是提醒一下
            Torque.LOGGER.warn("Unexpected bedrock geometry format_version {} (expected 1.12.0), parsing anyway", formatVersion);
        }

        JsonElement geometryElement = root.get("minecraft:geometry");
        if (geometryElement == null) {
            Torque.LOGGER.warn("Bedrock geometry file has no minecraft:geometry entry");
            return result;
        }

        if (geometryElement.isJsonArray()) {
            for (JsonElement element : geometryElement.getAsJsonArray()) {
                parseOne(element, result);
            }
        } else {
            parseOne(geometryElement, result);
        }

        return result;
    }

    private static void parseOne(JsonElement element, List<BedrockGeometry> result) {
        JsonObject json = BedrockJson.object(element);
        if (json == null) {
            Torque.LOGGER.warn("Skipping non-object entry in minecraft:geometry");
            return;
        }

        String identifier = null;
        try {
            JsonObject description = BedrockJson.object(json.get("description"));
            if (description == null) {
                throw new IllegalArgumentException("geometry has no description object");
            }

            identifier = BedrockJson.string(description.get("identifier"), null);
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalArgumentException("geometry description has no identifier");
            }

            int textureWidth = BedrockJson.integer(description.get("texture_width"), -1);
            int textureHeight = BedrockJson.integer(description.get("texture_height"), -1);
            if (textureWidth <= 0 || textureHeight <= 0) {
                Torque.LOGGER.warn("Bedrock geometry {} has no valid texture_width/height, defaulting to 64x64", identifier);
            }

            List<BedrockBone> bones = parseBones(json.get("bones"));
            result.add(new BedrockGeometry(identifier, textureWidth, textureHeight, bones));
        } catch (RuntimeException exception) {
            Torque.LOGGER.error("Failed to parse bedrock geometry {}", identifier == null ? "<unknown>" : identifier, exception);
        }
    }

    private static List<BedrockBone> parseBones(JsonElement element) {
        JsonArray array = BedrockJson.array(element);
        if (array == null) {
            return List.of();
        }

        List<BedrockBone> bones = new ArrayList<>();
        for (JsonElement boneElement : array) {
            JsonObject json = BedrockJson.object(boneElement);
            if (json == null) {
                continue;
            }

            String name = BedrockJson.string(json.get("name"), null);
            if (name == null || name.isBlank()) {
                Torque.LOGGER.warn("Skipping bedrock bone without a name");
                continue;
            }

            String parent = BedrockJson.string(json.get("parent"), null);
            if (parent != null && parent.isBlank()) {
                parent = null;
            }

            float[] pivot = new float[3];
            BedrockJson.vector(json.get("pivot"), pivot);

            float[] rotation = new float[3];
            BedrockJson.vector(json.get("rotation"), rotation);

            List<BedrockCube> cubes = parseCubes(json.get("cubes"));
            bones.add(new BedrockBone(name, parent, pivot, rotation, cubes));
        }

        return bones;
    }

    private static List<BedrockCube> parseCubes(JsonElement element) {
        JsonArray array = BedrockJson.array(element);
        if (array == null) {
            return List.of();
        }

        List<BedrockCube> cubes = new ArrayList<>();
        for (JsonElement cubeElement : array) {
            JsonObject json = BedrockJson.object(cubeElement);
            if (json == null) {
                continue;
            }

            float[] origin = new float[3];
            BedrockJson.vector(json.get("origin"), origin);

            float[] size = new float[3];
            BedrockJson.vector(json.get("size"), size);

            float inflate = BedrockJson.number(json.get("inflate"), 0.0F);
            boolean mirror = BedrockJson.bool(json.get("mirror"), false);

            float[] rotation = new float[3];
            BedrockJson.vector(json.get("rotation"), rotation);

            float[] uv = null;
            Map<String, FaceUV> faceUVs = Map.of();
            JsonElement uvElement = json.get("uv");
            if (uvElement != null && uvElement.isJsonArray()) {
                uv = new float[2];
                BedrockJson.vector(uvElement, uv);
            } else if (uvElement != null && uvElement.isJsonObject()) {
                faceUVs = parseFaceUvs(uvElement.getAsJsonObject(), size);
            }

            cubes.add(new BedrockCube(origin, size, inflate, mirror, rotation, uv, faceUVs));
        }

        return cubes;
    }

    /**
     * 解析 per-face uv：{@code {"north": {"uv": [u, v], "uv_size": [w, h]}, ...}}。
     * 没写 {@code uv_size} 时按该面的几何尺寸补（Bedrock 导出器基本都会写，这里只是兜底）。
     */
    private static Map<String, FaceUV> parseFaceUvs(JsonObject json, float[] size) {
        Map<String, FaceUV> faces = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String face = entry.getKey().toLowerCase(Locale.ROOT);
            if (!isFace(face)) {
                continue;
            }

            JsonObject faceJson = BedrockJson.object(entry.getValue());
            if (faceJson == null || faceJson.get("uv") == null) {
                continue;
            }

            float[] uv = new float[2];
            BedrockJson.vector(faceJson.get("uv"), uv);

            float[] uvSize = new float[2];
            if (faceJson.get("uv_size") != null) {
                BedrockJson.vector(faceJson.get("uv_size"), uvSize);
            } else {
                defaultFaceSize(face, size, uvSize);
            }

            faces.put(face, new FaceUV(uv[0], uv[1], uvSize[0], uvSize[1]));
        }

        return faces;
    }

    private static boolean isFace(String name) {
        for (String face : FACES) {
            if (face.equals(name)) {
                return true;
            }
        }

        return false;
    }

    /** 没写 uv_size 时的缺省尺寸：north/south = w×h，east/west = d×h，up/down = w×d。 */
    private static void defaultFaceSize(String face, float[] size, float[] out) {
        switch (face) {
            case "north", "south" -> {
                out[0] = size[0];
                out[1] = size[1];
            }
            case "east", "west" -> {
                out[0] = size[2];
                out[1] = size[1];
            }
            default -> {
                out[0] = size[0];
                out[1] = size[2];
            }
        }
    }

    @Override
    public String toString() {
        return "BedrockGeometry[" + this.identifier + ", bones=" + this.bones.size() + "]";
    }
}
