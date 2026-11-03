package com.zszq.torque.mixin;

import java.util.List;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问原版 {@code LivingEntityRenderer} 的非公开字段，用来把玩家渲染器换成资源包驱动的骨骼渲染：
 * <ul>
 *     <li>{@code model}：换成 {@code TorquePlayerModel}（不画原版皮肤）；</li>
 *     <li>{@code layers}：插皮肤层、替换盔甲 / 手持物品层、按需移除披风 / 鞘翅层。</li>
 * </ul>
 *
 * <p>用 Mixin accessor 而不是 AccessTransformer：项目本来就有 mixin 配置，
 * 不用改 build.gradle，也不会额外引入构建期 AT 配置。
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccessor {
    @Accessor("model")
    void torque$setModel(EntityModel<?> model);

    @SuppressWarnings("rawtypes")
    @Accessor("layers")
    List<RenderLayer> torque$getLayers();
}
