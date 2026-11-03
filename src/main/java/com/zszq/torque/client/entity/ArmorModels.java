package com.zszq.torque.client.entity;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * 按 {@link SkinnedArmorModel} 的骨架层级搭出原版盔甲网格（宽版 / 内层两套，纹理 64×32）。
 *
 * <p>cube 的尺寸 / UV 与原版 {@code HumanoidModel#createMesh} +
 * {@code HumanoidArmorModel#createBodyLayer} 完全一致，只是把层级改成 Bedrock 玩家几何体的：
 * root → bone_root → waist → body_pivot → {body, head, arms}；bone_root → legs。
 * 这样动画绕腰（geo body pivot = 模型 y=12）旋转时，胸甲、头盔、手臂都会一起动，
 * 和皮肤几何体保持同一套骨骼矩阵。
 *
 * <p>原版对宽版 / 窄版皮肤用的是同一套盔甲网格（{@code PLAYER_SLIM_*_ARMOR} 与
 * {@code PLAYER_*_ARMOR} 引用同一份定义），所以这里只需要 outer / inner 两套。
 */
public final class ArmorModels {
    private ArmorModels() {
    }

    public static SkinnedArmorModel createOuter() {
        return new SkinnedArmorModel(bake(LayerDefinitions.OUTER_ARMOR_DEFORMATION));
    }

    public static SkinnedArmorModel createInner() {
        return new SkinnedArmorModel(bake(LayerDefinitions.INNER_ARMOR_DEFORMATION));
    }

    private static ModelPart bake(CubeDeformation deformation) {
        return createMesh(deformation).getRoot().bake(64, 32);
    }

    private static MeshDefinition createMesh(CubeDeformation deformation) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition boneRoot = root.addOrReplaceChild("bone_root", CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));
        PartDefinition waist = boneRoot.addOrReplaceChild("waist", CubeListBuilder.create(), PartPose.offset(0.0F, -12.0F, 0.0F));
        PartDefinition bodyPivot = waist.addOrReplaceChild("body_pivot", CubeListBuilder.create(), PartPose.ZERO);

        // 身体：cube 相对原版 body pivot（模型 y=0），这里挂在腰下 12 像素，绝对位置仍是 0..12
        bodyPivot.addOrReplaceChild(
                "body",
                CubeListBuilder.create().texOffs(16, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, deformation),
                PartPose.offset(0.0F, -12.0F, 0.0F));

        // 头 + 帽子：head 挂在 body_pivot（绝对 y=0），hat 是 head 的子部件（绝对 y=0，尺寸外扩 0.5）
        PartDefinition head = bodyPivot.addOrReplaceChild(
                "head",
                CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, deformation),
                PartPose.offset(0.0F, -12.0F, 0.0F));
        head.addOrReplaceChild(
                "hat",
                CubeListBuilder.create().texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, deformation.extend(0.5F)),
                PartPose.ZERO);

        // 手臂：原版 pivot (∓5, 2)；相对 body_pivot（绝对 y=12）是 ∓5, -10
        bodyPivot.addOrReplaceChild(
                "left_arm",
                CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, deformation),
                PartPose.offset(5.0F, -10.0F, 0.0F));
        bodyPivot.addOrReplaceChild(
                "right_arm",
                CubeListBuilder.create().texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, deformation),
                PartPose.offset(-5.0F, -10.0F, 0.0F));

        // 腿：相对 bone_root（绝对 y=24）偏移 -12 => 绝对 y=12，与原版腿 pivot 相同
        CubeDeformation legDeformation = deformation.extend(HumanoidModel.LEGGINGS_OVERLAY_SCALE);
        boneRoot.addOrReplaceChild(
                "left_leg",
                CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, legDeformation),
                PartPose.offset(1.9F, -12.0F, 0.0F));
        boneRoot.addOrReplaceChild(
                "right_leg",
                CubeListBuilder.create().texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, legDeformation),
                PartPose.offset(-1.9F, -12.0F, 0.0F));

        return mesh;
    }
}
