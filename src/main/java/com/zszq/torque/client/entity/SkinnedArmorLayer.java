package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * 盔甲层：替代原版 {@code HumanoidArmorLayer}，用 {@link SkinnedArmorModel} 把盔甲套在
 * 几何体骨骼上。
 *
 * <p>渲染流程照原版 {@code HumanoidArmorLayer#renderArmorPiece}：按 {@code ArmorMaterial}
 * 的多层纹理（含染色）、盔甲纹饰 trim、附魔光效分别提交；区别只有两点：
 * <ul>
 *     <li>盔甲模型不复制原版 {@code PlayerModel} 的姿态，而是每帧
 *     {@link SkinnedArmorModel#applyPose} 从 {@link PlayerRig} 的骨骼姿态算；</li>
 *     <li>槽位部件可见性由 {@link SkinnedArmorModel#setPartVisibility} 控制。</li>
 * </ul>
 */
public class SkinnedArmorLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final SkinnedArmorModel outerModel;
    private final SkinnedArmorModel innerModel;
    private final TextureAtlas armorTrimAtlas;

    public SkinnedArmorLayer(
            RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
            ModelManager modelManager) {
        super(parent);
        this.outerModel = ArmorModels.createOuter();
        this.innerModel = ArmorModels.createInner();
        this.armorTrimAtlas = modelManager.getAtlas(Sheets.ARMOR_TRIMS_SHEET);
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
        this.renderArmorPiece(poseStack, buffer, packedLight, player, EquipmentSlot.CHEST, this.outerModel, partialTicks);
        this.renderArmorPiece(poseStack, buffer, packedLight, player, EquipmentSlot.LEGS, this.innerModel, partialTicks);
        this.renderArmorPiece(poseStack, buffer, packedLight, player, EquipmentSlot.FEET, this.outerModel, partialTicks);
        this.renderArmorPiece(poseStack, buffer, packedLight, player, EquipmentSlot.HEAD, this.outerModel, partialTicks);
    }

    private void renderArmorPiece(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            AbstractClientPlayer player,
            EquipmentSlot slot,
            SkinnedArmorModel model,
            float partialTicks) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!(stack.getItem() instanceof ArmorItem armorItem) || armorItem.getEquipmentSlot() != slot) {
            return;
        }

        boolean innerTexture = slot == EquipmentSlot.LEGS;
        model.setPartVisibility(slot);
        model.applyPose(player, partialTicks);

        Holder<ArmorMaterial> materialHolder = armorItem.getMaterial();
        ArmorMaterial material = materialHolder.value();
        IClientItemExtensions extensions = IClientItemExtensions.of(stack);
        int fallbackColor = extensions.getDefaultDyeColor(stack);

        for (int layerIndex = 0; layerIndex < material.layers().size(); layerIndex++) {
            ArmorMaterial.Layer layer = material.layers().get(layerIndex);
            int color = extensions.getArmorLayerTintColor(stack, player, layer, layerIndex, fallbackColor);
            if (color == 0) {
                continue;
            }

            ResourceLocation texture = ClientHooks.getArmorTexture(player, stack, layer, innerTexture, slot);
            VertexConsumer vertices = buffer.getBuffer(RenderType.armorCutoutNoCull(texture));
            model.renderToBuffer(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, color);
        }

        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim != null) {
            TextureAtlasSprite sprite = this.armorTrimAtlas.getSprite(
                    innerTexture ? trim.innerTexture(materialHolder) : trim.outerTexture(materialHolder));
            VertexConsumer vertices = sprite.wrap(buffer.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));
            model.renderToBuffer(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY, -1);
        }

        if (stack.hasFoil()) {
            model.renderToBuffer(poseStack, buffer.getBuffer(RenderType.armorEntityGlint()), packedLight, OverlayTexture.NO_OVERLAY, -1);
        }
    }
}
