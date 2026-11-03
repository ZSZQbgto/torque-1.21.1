package com.zszq.torque.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 引擎专用渲染类型。
 *
 * <p>{@link #BONE_LINES}：F3 骨骼框架。和原版 {@code RenderType.lines()} 的区别是
 * <b>关闭深度测试</b>（{@code NO_DEPTH_TEST}）——骨骼线框在角色身体内部，开深度测试会被
 * 皮肤几何体挡住，F3 下就什么都看不到。线宽 2.0，颜色写入顶点，不写深度。
 */
public final class TorqueRenderTypes {
    /** 骨骼框架：POSITION_COLOR_NORMAL 线段，无深度测试、颜色写入、线宽 2。 */
    public static final RenderType BONE_LINES = RenderType.create(
            "torque_bone_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                    .setLayeringState(RenderStateShard.NO_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    private TorqueRenderTypes() {
    }
}
