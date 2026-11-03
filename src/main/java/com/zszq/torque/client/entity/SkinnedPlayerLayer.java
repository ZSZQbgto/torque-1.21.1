package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zszq.torque.client.bedrock.SkeletonPose;
import com.zszq.torque.client.render.SkeletonMeshRenderer;
import com.zszq.torque.client.render.SkeletonWireframe;
import com.zszq.torque.client.render.TorqueRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.joml.Matrix4f;

/**
 * 皮肤层：用资源包加载的 Bedrock 几何体替换原版玩家皮肤网格。
 *
 * <p>坐标：进入本层时 PoseStack 已经是“模型空间”（原版 {@code LivingEntityRenderer} 已经
 * 乘过 {@code Ry(180-bodyRot) · S(0.9375) · S(-1,-1,1) · T(0,-1.501,0)}）。
 * 这里只补 geo space → model space 那一步：
 * {@code translate(0, 24/16, 0); scale(1, -1, 1)}，之后所有几何体坐标直接用 geo 像素 / 16。
 *
 * <p>F3：{@code Minecraft#getDebugOverlay().showDebugScreen()} 为 true 时，在同一套 PoseStack
 * 里用 {@link RenderType#lines()} 画骨骼框架——因为 PoseStack 已经带着实体位移 / 朝向，
 * 线框会准确套在角色身上（第三人称的自己、其它玩家都显示）。
 */
public class SkinnedPlayerLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    /** 隐身但对自己可见时的透明度，与原版一致 */
    private static final int INVISIBLE_COLOR = 0x26FFFFFF;
    /** 骨骼框架主色（金色） */
    private static final int BONE_COLOR = 0xFFFFD700;

    public SkinnedPlayerLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            AbstractClientPlayer player,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        Minecraft minecraft = Minecraft.getInstance();
        // 对自己也隐身 = 完全看不见；否则按原版半透明画（和原版 isBodyVisible 语义一致）
        if (player.isInvisibleTo(minecraft.player)) {
            return;
        }

        SkeletonPose pose = PlayerRig.INSTANCE.poseFor(player, partialTicks);
        if (pose == null) {
            // 资源包还没加载 / rig 没有有效几何体：什么都不画，原版皮肤已由 TorquePlayerModel 关掉
            return;
        }

        int color = player.isInvisible() ? INVISIBLE_COLOR : -1;
        int overlay = LivingEntityRenderer.getOverlayCoords(player, 0.0F);
        RenderType renderType = RenderType.entityTranslucent(player.getSkin().texture());
        VertexConsumer vertices = buffer.getBuffer(renderType);

        poseStack.pushPose();
        // geo space → model space：geo 的脚在 y=0、Y 向上；模型空间 Y 向下、脚在 y=24 像素
        poseStack.translate(0.0F, 24.0F / 16.0F, 0.0F);
        poseStack.scale(1.0F, -1.0F, 1.0F);

        SkeletonMeshRenderer.render(
                vertices,
                poseStack.last(),
                pose.skeleton(),
                pose,
                packedLight,
                overlay,
                color,
                PlayerRig.INSTANCE.hiddenBones());

        if (minecraft.getDebugOverlay().showDebugScreen()) {
            VertexConsumer lines = buffer.getBuffer(TorqueRenderTypes.BONE_LINES);
            // 当前 PoseStack 已经含 geo→model 与实体变换，所以 geoToWorld 传单位矩阵
            SkeletonWireframe.emit(lines, poseStack.last(), pose.skeleton(), pose, new Matrix4f(), BONE_COLOR);
        }

        poseStack.popPose();
    }
}
