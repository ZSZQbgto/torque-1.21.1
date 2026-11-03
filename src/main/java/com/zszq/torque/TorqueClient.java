package com.zszq.torque;

import com.zszq.torque.client.bedrock.AnimationLibrary;
import com.zszq.torque.client.bedrock.BedrockGeometry;
import com.zszq.torque.client.bedrock.GeometryLibrary;
import com.zszq.torque.client.bedrock.RigDefinition;
import com.zszq.torque.client.bedrock.RigLibrary;
import com.zszq.torque.client.entity.PlayerRig;
import com.zszq.torque.client.entity.SkinnedArmorLayer;
import com.zszq.torque.client.entity.SkinnedItemLayer;
import com.zszq.torque.client.entity.SkinnedPlayerLayer;
import com.zszq.torque.client.entity.TorquePlayerModel;
import com.zszq.torque.mixin.LivingEntityRendererAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口：把资源包驱动的骨骼动画引擎接进原版玩家渲染。
 *
 * <p>做的事很少，全部是“接线”：
 * <ol>
 *     <li>注册三个资源监听器（几何体 / 动画 / rig 绑定），F3+T 会重新加载；</li>
 *     <li>在 {@code AddLayers} 里替换玩家模型与渲染层：皮肤 → 骨骼几何体，盔甲 → 骨骼盔甲，
 *         手持物品 → 物品骨骼；</li>
 *     <li>资源加载完成后按 rig 的 {@code hideVanilla} 移除原版披风 / 鞘翅层（几何体自带对应部件时）。</li>
 * </ol>
 */
@Mod(value = Torque.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Torque.MODID, value = Dist.CLIENT)
public class TorqueClient {
    /** AddLayers 里配置过的玩家渲染器，等资源加载完后用来按需移除原版层 */
    private static final List<PlayerRenderer> CONFIGURED_RENDERERS = new ArrayList<>();
    private static boolean vanillaLayersResolved;

    public TorqueClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // ClientTickEvent 是游戏总线事件，不能挂在 MOD 总线的 @EventBusSubscriber 上
        NeoForge.EVENT_BUS.addListener(TorqueClient::onClientTick);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Torque.LOGGER.info("Torque animation engine initializing (resource-pack driven bedrock animations)");
    }

    /** 三个数据来源全部挂到客户端资源重载链：F3+T / 换资源包即生效。 */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(GeometryLibrary.INSTANCE);
        event.registerReloadListener(AnimationLibrary.INSTANCE);
        event.registerReloadListener(RigLibrary.INSTANCE);
    }

    /**
     * 玩家渲染器接线：
     * <ul>
     *     <li>模型换成 {@link TorquePlayerModel}（不画原版皮肤）；</li>
     *     <li>渲染层列表头部插入 {@link SkinnedPlayerLayer}（骨骼几何体 + F3 线框）；</li>
     *     <li>原地替换原版盔甲层 / 手持物品层（保持原有渲染顺序与深度关系）。</li>
     * </ul>
     */
    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<? extends Player> renderer = event.getSkin(skin);
            if (!(renderer instanceof PlayerRenderer playerRenderer)) {
                continue;
            }

            boolean slim = skin == PlayerSkin.Model.SLIM;
            ModelPart root = event.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
            LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) (Object) playerRenderer;
            accessor.torque$setModel(new TorquePlayerModel(root, slim));

            List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layers = layersOf(accessor);

            for (int i = 0; i < layers.size(); i++) {
                RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> layer = layers.get(i);
                if (layer instanceof HumanoidArmorLayer<?, ?, ?>) {
                    layers.set(i, new SkinnedArmorLayer(playerRenderer, event.getContext().getModelManager()));
                } else if (layer instanceof PlayerItemInHandLayer<?, ?>) {
                    layers.set(i, new SkinnedItemLayer(playerRenderer, event.getContext().getItemInHandRenderer()));
                }
            }

            // 皮肤层必须最先画（列表越靠前越先画），盔甲 / 物品层才能正确盖在上面
            layers.add(0, new SkinnedPlayerLayer(playerRenderer));
            CONFIGURED_RENDERERS.add(playerRenderer);
        }
    }

    /**
     * 资源加载完成后再处理原版层：几何体自带 {@code cape} 骨骼（或 rig 的 {@code hideVanilla}
     * 指定了 cape / wings）时，移除原版对应层，避免和几何体里的部件重复渲染。
     * AddLayers 触发时资源还没加载，所以延后到这里做，只做一次。
     */
    private static void onClientTick(ClientTickEvent.Post event) {
        if (vanillaLayersResolved) {
            return;
        }

        RigDefinition rig = RigLibrary.player();
        BedrockGeometry geometry = GeometryLibrary.get(rig.geometry());
        if (geometry == null) {
            return;
        }

        vanillaLayersResolved = true;
        boolean hideCape = rig.hideVanilla().contains("cape") || geometry.bone("cape") != null;
        boolean hideWings = rig.hideVanilla().contains("wings");

        for (PlayerRenderer renderer : CONFIGURED_RENDERERS) {
            LivingEntityRendererAccessor accessor = (LivingEntityRendererAccessor) (Object) renderer;
            List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layers = layersOf(accessor);
            if (hideCape) {
                layers.removeIf(layer -> layer instanceof CapeLayer);
            }
            if (hideWings) {
                layers.removeIf(layer -> layer instanceof ElytraLayer);
            }
        }

        Torque.LOGGER.info("Torque player rig ready: geometry={}, capeOverridden={}", rig.geometry(), hideCape);
        // 玩家实体没渲染过时也可以提前把骨架建出来，方便日志排查
        if (Minecraft.getInstance().level != null && PlayerRig.INSTANCE.skeleton() == null) {
            Torque.LOGGER.warn("Torque skeleton not available (rig={})", rig);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>> layersOf(LivingEntityRendererAccessor accessor) {
        return (List) accessor.torque$getLayers();
    }
}
