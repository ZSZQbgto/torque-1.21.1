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
 * 几何体资源表：扫描 {@code assets/<namespace>/geometries/**.json}，按
 * {@code description.identifier}（如 {@code geometry.default_player}）索引。
 *
 * <p>走原版资源重载流程，所以资源包同路径覆盖即可替换模组自带几何体，无需重启。
 *
 * <p><b>线程模型</b>：{@link #prepare(ResourceManager, ProfilerFiller) prepare} 在后台线程解析并返回
 * 不可变快照；{@link #apply(Map, ResourceManager, ProfilerFiller) apply} 在客户端主线程原子替换引用；
 * {@link #get(String)} / {@link #all()} 永远读到"某一个完整快照"，重载过程中不会读到半成品或 null。
 */
public final class GeometryLibrary extends SimplePreparableReloadListener<Map<String, BedrockGeometry>> {
    public static final GeometryLibrary INSTANCE = new GeometryLibrary();

    /** 资源包里的目录名：assets/&lt;namespace&gt;/geometries */
    public static final String DIRECTORY = "geometries";

    /** 当前快照。apply() 里整体替换，volatile 保证其它线程看到的是完整的新 map。 */
    private volatile Map<String, BedrockGeometry> geometries = Map.of();

    private GeometryLibrary() {
    }

    @Override
    protected Map<String, BedrockGeometry> prepare(ResourceManager manager, ProfilerFiller profiler) {
        profiler.push("torque/geometries");

        Map<String, BedrockGeometry> loaded = new HashMap<>();
        FileToIdConverter lister = FileToIdConverter.json(DIRECTORY);
        Map<ResourceLocation, Resource> resources = lister.listMatchingResources(manager);

        // 资源包里同一个 identifier 可能被多个文件定义；按文件名排序让结果稳定可复现
        List<ResourceLocation> files = new ArrayList<>(resources.keySet());
        files.sort(Comparator.comparing(ResourceLocation::toString));

        for (ResourceLocation file : files) {
            try (Reader reader = resources.get(file).openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (BedrockGeometry geometry : BedrockGeometry.parseAll(root)) {
                    BedrockGeometry previous = loaded.put(geometry.identifier(), geometry);
                    if (previous != null) {
                        Torque.LOGGER.warn("Duplicate bedrock geometry identifier {}, overriding {} with {}", geometry.identifier(), previous, geometry);
                    }
                }
            } catch (Exception exception) {
                // 单个文件失败只记录，不影响其它文件和整个重载流程
                Torque.LOGGER.error("Failed to read bedrock geometry file {}", file, exception);
            }
        }

        profiler.pop();
        return Map.copyOf(loaded);
    }

    @Override
    protected void apply(Map<String, BedrockGeometry> prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.geometries = prepared;
        Torque.LOGGER.info("Loaded {} bedrock geometries from resource packs", prepared.size());
    }

    /** 按 {@code description.identifier} 取几何体；没有加载到返回 null。 */
    public static BedrockGeometry get(String identifier) {
        if (identifier == null) {
            return null;
        }

        return INSTANCE.geometries.get(identifier);
    }

    /** 当前快照（不可变）。 */
    public static Map<String, BedrockGeometry> all() {
        return INSTANCE.geometries;
    }
}
