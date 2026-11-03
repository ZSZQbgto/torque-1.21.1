package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zszq.torque.client.bedrock.RigDefinition;
import com.zszq.torque.client.bedrock.SkeletonPose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 手持物品层：把物品画在几何体的 {@code rightItem} / {@code leftItem} 骨骼上。
 *
 * <p>物品骨骼本身在 rig json 里配置（默认 {@code rightItem} / {@code leftItem}），
 * 可以随动画一起动——占位 cube 由 {@code hiddenBones} 跳过，真正的物品在这里渲染。
 *
 * <p>坐标：PoseStack 先补 geo→model 换算，再按骨骼链压入
 * {@code T(pivot+pos)/16 · Rz·Ry·Rx · S · T(-pivot)/16}，到达物品骨骼的 pivot；
 * 之后沿用原版 {@code ItemInHandLayer} 的朝向（{@code Rx(-90°) · Ry(180°)}）提交物品模型。
 * 原版那 10 像素的前移是给“从肩关节出发”的直筒手臂用的，这里骨骼 pivot 已经在手心，故不再补。
 */
public class SkinnedItemLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final ItemInHandRenderer itemRenderer;

    public SkinnedItemLayer(
            RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
            ItemInHandRenderer itemRenderer) {
        super(parent);
        this.itemRenderer = itemRenderer;
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
        if (player.isSpectator()) {
            return;
        }

        SkeletonPose pose = PlayerRig.INSTANCE.poseFor(player, partialTicks);
        if (pose == null) {
            return;
        }

        RigDefinition rig = PlayerRig.INSTANCE.rig();
        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        // 和原版 ItemInHandLayer 一致：右手拿主手（右手惯用）或副手（左手惯用）
        ItemStack rightStack = rightHanded ? player.getMainHandItem() : player.getOffhandItem();
        ItemStack leftStack = rightHanded ? player.getOffhandItem() : player.getMainHandItem();

        this.renderHand(poseStack, buffer, packedLight, player, pose, rig.rightItemBone(),
                HumanoidArm.RIGHT, rightStack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
        this.renderHand(poseStack, buffer, packedLight, player, pose, rig.leftItemBone(),
                HumanoidArm.LEFT, leftStack, ItemDisplayContext.THIRD_PERSON_LEFT_HAND);
    }

    private void renderHand(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            AbstractClientPlayer player,
            SkeletonPose pose,
            String boneName,
            HumanoidArm arm,
            ItemStack stack,
            ItemDisplayContext displayContext) {
        if (stack.isEmpty() || boneName == null) {
            return;
        }

        int bone = pose.skeleton().indexOf(boneName);
        if (bone < 0) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(0.0F, 24.0F / 16.0F, 0.0F);
        poseStack.scale(1.0F, -1.0F, 1.0F);
        PlayerRig.applyBoneChain(poseStack, pose.skeleton(), pose, bone);

        // 与原版 ItemInHandLayer 相同的坐标系转换（骨骼 pivot 已经在手心，不再补 10 像素前移）
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));

        this.itemRenderer.renderItem(
                player,
                stack,
                displayContext,
                arm == HumanoidArm.LEFT,
                poseStack,
                buffer,
                packedLight);
        poseStack.popPose();
    }
}
