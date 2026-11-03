package com.zszq.torque.client.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zszq.torque.Torque;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * 动画资源表：扫描 {@code assets/<namespace>/animations/**.json}，一个文件里 {@code animations}
 * 下挂几条就收几条，按动画名（如 {@code walk}、{@code animation.idle}）索引。
 *
 * <p>与 {@link GeometryLibrary} 一样走原版资源重载：{@code prepare()} 在后台线程解析并返回不可变快照，
 * {@code apply()} 原子替换引用，{@link #get(String)} 任何时刻都能读到某个完整快照。
 */
public final class AnimationLibrary extends SimplePreparableReloadListener<Map<String, BedrockAnimation>> {
    public static final AnimationLibrary INSTANCE = new AnimationLibrary();

    /** 资源包里的目录名：assets/&lt;namespace&gt;/animations */
    public static final String DIRECTORY = "animations";

    private volatile Map<String, BedrockAnimation> animations = Map.of();

    private AnimationLibrary() {
    }

    @Override
    protected Map<String, BedrockAnimation> prepare(ResourceManager manager, ProfilerFiller profiler) {
        profiler.push("torque/animations");

        Map<String, BedrockAnimation> loaded = new HashMap<>();
        FileToIdConverter lister = FileToIdConverter.json(DIRECTORY);
        Map<ResourceLocation, Resource> resources = lister.listMatchingResources(manager);

        List<ResourceLocation> files = new ArrayList<>(resources.keySet());
        files.sort(Comparator.comparing(ResourceLocation::toString));

        for (ResourceLocation file : files) {
            try (Reader reader = resources.get(file).openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, BedrockAnimation> entry : BedrockAnimation.parseAll(root).entrySet()) {
                    BedrockAnimation previous = loaded.put(entry.getKey(), entry.getValue());
                    if (previous != null) {
                        Torque.LOGGER.warn("Duplicate bedrock animation name {}, overriding {} with {}", entry.getKey(), previous, entry.getValue());
                    }
                }
            } catch (Exception exception) {
                Torque.LOGGER.error("Failed to read bedrock animation file {}", file, exception);
            }
        }

        profiler.pop();
        return Map.copyOf(loaded);
    }

    @Override
    protected void apply(Map<String, BedrockAnimation> prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.animations = prepared;
        Torque.LOGGER.info("Loaded {} bedrock animations from resource packs", prepared.size());
    }

    /** 按动画名取；没有加载到返回 null。 */
    public static BedrockAnimation get(String name) {
        if (name == null) {
            return null;
        }

        return INSTANCE.animations.get(name);
    }
}
