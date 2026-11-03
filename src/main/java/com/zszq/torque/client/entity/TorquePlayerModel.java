package com.zszq.torque.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * 玩家模型：原版皮肤部件不再由模型本身绘制。
 *
 * <p>皮肤改由 {@link SkinnedPlayerLayer} 用资源包里的 Bedrock 几何体裁剪绘制
 * （骨骼 / UV / 动作全部来自 {@code assets/<ns>/geometries} 与 {@code animations}），
 * 所以这里把 {@code renderToBuffer} 直接置空——原版的头、身、四肢、外套、裤子都不会画，
 * 但模型部件对象仍然存在且保持原版姿态，第一人称 {@code PlayerRenderer#renderHand}
 * 直接渲染 {@code rightArm} / {@code leftArm}，所以第一人称的手不受影响。
 *
 * <p>注意：不能靠把部件 {@code visible=false} 来隐藏——{@code AgeableListModel#renderToBuffer}
 * 会重新按 {@code headParts()/bodyParts()} 渲染，而外套 / 裤子等部件是 {@code copyFrom} 同步的，
 * 直接空实现最干净，也不会影响其它层对部件的姿态读取。
 */
public class TorquePlayerModel extends PlayerModel<AbstractClientPlayer> {
    public TorquePlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        // 皮肤由 SkinnedPlayerLayer 绘制，见类注释。
    }
}
