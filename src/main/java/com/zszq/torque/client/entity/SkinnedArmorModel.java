package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.zszq.torque.client.bedrock.Skeleton;
import com.zszq.torque.client.bedrock.SkeletonPose;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * 盔甲模型：按 Bedrock 玩家几何体的骨骼层级重新搭的原版盔甲网格。
 *
 * <p>原版 {@code HumanoidArmorLayer} 用 {@code copyPropertiesTo} 把玩家模型部件的姿态
 * 抄给盔甲模型，而原版 {@code body} 部件 pivot 在颈部、Bedrock geo 的 {@code body} pivot
 * 在腰部，套过去会错位。这里按 geo 的层级重搭（像素、model space）：
 *
 * <pre>
 * root
 *   bone_root (0,24,0)         [geo root]
 *     waist (0,-12,0)          [geo waist]      绝对 y=12
 *       body_pivot (0,0,0)     [geo body]       绝对 y=12（腰）
 *         body (0,-12,0)       胸甲 cube，绝对 y 0..12
 *         head (0,-12,0)       [geo head]       绝对 y=0
 *           hat
 *         left_arm (5,-10,0)   [geo leftArm]    绝对 y=2
 *         right_arm (-5,-10,0) [geo rightArm]
 *     left_leg (1.9,-12,0)     [geo leftLeg]    绝对 y=12
 *     right_leg (-1.9,-12,0)   [geo rightLeg]
 * </pre>
 *
 * <p>每帧 {@link #applyPose} 把 {@link SkeletonPose} 的骨骼旋转 / 位移换算到模型空间：
 * 欧拉角 {@code (-rx, ry, -rz)}、位移 {@code (dx, -dy, dz)}（见 docs/animation-engine.md §2.4）。
 */
public class SkinnedArmorModel extends Model {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final ModelPart root;
    private final ModelPart boneRoot;
    private final ModelPart waist;
    private final ModelPart bodyPivot;
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart hat;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    public SkinnedArmorModel(ModelPart root) {
        super(RenderType::armorCutoutNoCull);
        this.root = root;
        this.boneRoot = root.getChild("bone_root");
        this.waist = this.boneRoot.getChild("waist");
        this.bodyPivot = this.waist.getChild("body_pivot");
        this.body = this.bodyPivot.getChild("body");
        this.head = this.bodyPivot.getChild("head");
        this.hat = this.head.getChild("hat");
        this.leftArm = this.bodyPivot.getChild("left_arm");
        this.rightArm = this.bodyPivot.getChild("right_arm");
        this.leftLeg = this.boneRoot.getChild("left_leg");
        this.rightLeg = this.boneRoot.getChild("right_leg");
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }

    /** 只让这个槽位需要的部件可见（和原版 {@code HumanoidArmorLayer#setPartVisibility} 一致）。 */
    public void setPartVisibility(EquipmentSlot slot) {
        for (EquipmentSlot each : ARMOR_SLOTS) {
            boolean visible = each == slot;
            switch (each) {
                case HEAD -> {
                    this.head.visible = visible;
                    this.hat.visible = visible;
                }
                case CHEST -> {
                    this.body.visible = visible;
                    this.leftArm.visible = visible;
                    this.rightArm.visible = visible;
                }
                case LEGS -> {
                    this.body.visible = visible;
                    this.leftLeg.visible = visible;
                    this.rightLeg.visible = visible;
                }
                case FEET -> {
                    this.leftLeg.visible = visible;
                    this.rightLeg.visible = visible;
                }
                default -> {
                }
            }
        }
    }

    /** 把骨骼姿态应用到盔甲部件。每帧、每个槽位渲染前调用一次。 */
    public void applyPose(AbstractClientPlayer player, float partialTicks) {
        this.root.getAllParts().forEach(ModelPart::resetPose);

        SkeletonPose pose = PlayerRig.INSTANCE.poseFor(player, partialTicks);
        if (pose == null) {
            return;
        }

        Skeleton skeleton = pose.skeleton();
        applyBone(this.boneRoot, skeleton, pose, "root");
        applyBone(this.waist, skeleton, pose, "waist");
        applyBone(this.bodyPivot, skeleton, pose, "body");
        applyBone(this.head, skeleton, pose, "head");
        applyBone(this.leftArm, skeleton, pose, "leftArm");
        applyBone(this.rightArm, skeleton, pose, "rightArm");
        applyBone(this.leftLeg, skeleton, pose, "leftLeg");
        applyBone(this.rightLeg, skeleton, pose, "rightLeg");
    }

    private static void applyBone(ModelPart part, Skeleton skeleton, SkeletonPose pose, String boneName) {
        int index = skeleton.indexOf(boneName);
        if (index < 0) {
            return;
        }

        float[] rotation = pose.rotation(index);   // 度，geo 轴
        part.xRot = (float) Math.toRadians(-rotation[0]);
        part.yRot = (float) Math.toRadians(rotation[1]);
        part.zRot = (float) Math.toRadians(-rotation[2]);

        float[] position = pose.position(index);   // geo 像素
        part.x += position[0];
        part.y -= position[1];
        part.z += position[2];

        float[] scale = pose.scale(index);
        part.xScale = scale[0];
        part.yScale = scale[1];
        part.zScale = scale[2];
    }
}
