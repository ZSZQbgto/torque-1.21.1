package com.zszq.torque.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zszq.torque.Torque;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * rig 绑定表：扫描 {@code assets/<namespace>/rigs/**.json}，按资源路径 id 索引
 * （{@code assets/torque/rigs/player.json} → {@code torque:player}）。
 *
 * <p>玩家默认使用 {@link #PLAYER_RIG}；{@link #player()} 在资源没加载到或解析失败时返回
 * {@link RigDefinition#EMPTY}，调用方据此退回静止姿势，不会 NPE。
 *
 * <p>线程模型同 {@link GeometryLibrary}：后台解析、主线程原子替换、静态 get 读完整快照。
 */
public final class RigLibrary extends SimplePreparableReloadListener<Map<ResourceLocation, RigDefinition>> {
    public static final RigLibrary INSTANCE = new RigLibrary();

    /** 资源包里的目录名：assets/&lt;namespace&gt;/rigs */
    public static final String DIRECTORY = "rigs";

    /** 玩家默认绑定的资源 id：{@code assets/torque/rigs/player.json}。 */
    public static final ResourceLocation PLAYER_RIG = ResourceLocation.fromNamespaceAndPath("torque", "player");

    /** 文档给出的 walk_position 缺省循环长度：2π / 0.6662 ≈ 9.4333。 */
    private static final float DEFAULT_WALK_POSITION_PER_CYCLE = 9.4333F;

    private volatile Map<ResourceLocation, RigDefinition> rigs = Map.of();

    private RigLibrary() {
    }

    @Override
    protected Map<ResourceLocation, RigDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        profiler.push("torque/rigs");

        Map<ResourceLocation, RigDefinition> loaded = new HashMap<>();
        FileToIdConverter lister = FileToIdConverter.json(DIRECTORY);
        Map<ResourceLocation, Resource> resources = lister.listMatchingResources(manager);

        List<ResourceLocation> files = new ArrayList<>(resources.keySet());
        files.sort(Comparator.comparing(ResourceLocation::toString));

        for (ResourceLocation file : files) {
            ResourceLocation id = lister.fileToId(file);
            try (Reader reader = resources.get(file).openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                RigDefinition definition = parse(root, id);
                RigDefinition previous = loaded.put(id, definition);
                if (previous != null) {
                    Torque.LOGGER.warn("Duplicate bedrock rig {}, overriding {}", id, previous);
                }
            } catch (Exception exception) {
                Torque.LOGGER.error("Failed to read bedrock rig {}", file, exception);
            }
        }

        profiler.pop();
        return Map.copyOf(loaded);
    }

    @Override
    protected void apply(Map<ResourceLocation, RigDefinition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.rigs = prepared;
        Torque.LOGGER.info("Loaded {} bedrock rigs from resource packs", prepared.size());
    }

    /** 玩家绑定；没加载到或解析失败时返回 {@link RigDefinition#EMPTY}（非 null）。 */
    public static RigDefinition player() {
        RigDefinition definition = INSTANCE.rigs.get(PLAYER_RIG);
        return definition == null ? RigDefinition.EMPTY : definition;
    }

    /** 按 rig 的资源 id 取；没有返回 null。 */
    public static RigDefinition get(ResourceLocation id) {
        if (id == null) {
            return null;
        }

        return INSTANCE.rigs.get(id);
    }

    private static RigDefinition parse(JsonObject root, ResourceLocation id) {
        String geometry = BedrockJson.string(root.get("geometry"), null);
        if (geometry == null || geometry.isBlank()) {
            throw new IllegalArgumentException("rig " + id + " has no geometry");
        }

        Map<String, RigAnimation> animations = new HashMap<>();
        JsonObject animationsJson = BedrockJson.object(root.get("animations"));
        if (animationsJson != null) {
            for (Map.Entry<String, JsonElement> entry : animationsJson.entrySet()) {
                parseSlot(entry.getKey(), entry.getValue(), animations);
            }
        }

        String rightItemBone = "rightItem";
        String leftItemBone = "leftItem";
        JsonObject itemsJson = BedrockJson.object(root.get("items"));
        if (itemsJson != null) {
            rightItemBone = nonBlank(BedrockJson.string(itemsJson.get("right"), null), rightItemBone);
            leftItemBone = nonBlank(BedrockJson.string(itemsJson.get("left"), null), leftItemBone);
        }

        float walkPositionPerCycle = BedrockJson.number(root.get("walkPositionPerCycle"), DEFAULT_WALK_POSITION_PER_CYCLE);

        Set<String> hideVanilla = new LinkedHashSet<>();
        JsonArray hideArray = BedrockJson.array(root.get("hideVanilla"));
        if (hideArray != null) {
            for (JsonElement element : hideArray) {
                String layer = BedrockJson.string(element, null);
                if (layer != null && !layer.isBlank()) {
                    hideVanilla.add(layer.toLowerCase(Locale.ROOT));
                }
            }
        }

        return new RigDefinition(geometry, animations, rightItemBone, leftItemBone, walkPositionPerCycle, hideVanilla);
    }

    /**
     * 解析一个语义槽位：标准写法是 {@code {"clip": "...", "clock": "time"}}，
     * 也接受简写成字符串 {@code "slot": "animation.x"}（等价于 clock = time）。
     */
    private static void parseSlot(String slot, JsonElement element, Map<String, RigAnimation> animations) {
        JsonObject slotJson = BedrockJson.object(element);
        if (slotJson == null) {
            String clip = BedrockJson.string(element, null);
            if (clip != null && !clip.isBlank()) {
                animations.put(slot, new RigAnimation(clip, AnimationClock.TIME));
            } else {
                Torque.LOGGER.warn("Skipping invalid rig slot {}", slot);
            }
            return;
        }

        String clip = BedrockJson.string(slotJson.get("clip"), null);
        if (clip == null || clip.isBlank()) {
            Torque.LOGGER.warn("Rig slot {} has no clip", slot);
            return;
        }

        AnimationClock clock = parseClock(BedrockJson.string(slotJson.get("clock"), "time"));
        animations.put(slot, new RigAnimation(clip, clock));
    }

    private static AnimationClock parseClock(String name) {
        if (name == null) {
            return AnimationClock.TIME;
        }

        return switch (name.toLowerCase(Locale.ROOT)) {
            case "walk_position", "walkposition", "walk-position" -> AnimationClock.WALK_POSITION;
            default -> AnimationClock.TIME;
        };
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
