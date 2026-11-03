# Torque

Minecraft **1.21.1** / NeoForge **21.1.265** 的玩家骨骼动画引擎：把 Blockbench / Bedrock 的
`*.geo.json`（几何体）和 `*.animation.json`（动画）当作资源包资源加载，用运行时骨骼驱动，
**不把任何骨骼名、UV、关键帧写死在 Java 里**。

- 设计 / 坐标关系 / 资源格式 / API 契约：[docs/animation-engine.md](docs/animation-engine.md)
- 演示资源包（覆盖替换用）：`tools/resourcepacks/torque-anim-demo/`

## 它能做什么

| 能力 | 说明 |
| --- | --- |
| 资源包驱动 | 几何体、动画、rig 绑定全部来自 `assets/<ns>/{geometries,animations,rigs}`，资源包同路径覆盖，F3+T 生效，无需重启 / 重编译 |
| 统一骨骼 | `Skeleton` 由任意 Bedrock 几何体建骨骼树；`SkeletonPose` 每帧按权重混合多条动画 |
| 关节贴图 | `SkeletonMeshRenderer` 按几何体的 cube / box-uv / per-face-uv / mirror / inflate / cube-rotation 铺带贴图网格 |
| F3 骨骼框架 | F3 打开时用无深度测试的线段画父骨骼 → 子骨骼连线 + pivot 点（金色 / 根红色 / 关节白） |
| 盔甲适配 | 按 Bedrock 层级重搭的原版盔甲网格，跟随骨骼的腰 / 头 / 手臂 / 腿旋转，支持染色 / trim / 附魔光效 |
| 手持物品 | `rightItem` / `leftItem` 是骨骼树的一部分；占位 cube 隐藏，真实物品用 `ItemInHandRenderer` 画在物品骨骼上，跟随动画 |
| 玩家模型 | 原版皮肤网格不再绘制，第一人称手保持原版；第三人称完全由几何体 + 动画驱动 |

## 资源包怎么用

模组自带：

```
assets/torque/geometries/player.geo.json
assets/torque/animations/player.animation.json
assets/torque/animations/idle.animation.json
assets/torque/rigs/player.json
```

想换动作 / 换模型：

1. 在资源包里放同路径文件（namespace 也用 `torque`），例如
   `assets/torque/animations/player.animation.json`；
2. 在游戏里 F3+T 重载（或重新进资源包界面应用）；
3. 动画名和 rig 的槽位对不上时改 `assets/torque/rigs/player.json`，不要改代码。

`tools/resourcepacks/torque-anim-demo/` 是一份可直接塞进 `run/resourcepacks/` 的示例：
它覆盖了 `player.animation.json`，用明显夸张的摆臂 / 抬腿动作验证资源包覆盖是否生效，
并且给 `rightItem` / `leftItem` 骨骼加了摇摆关键帧（验证手持物品跟随物品骨骼）。

## 构建 / 运行

```bash
./gradlew compileJava   # 编译
./gradlew runClient     # 开发客户端（run/ 目录，首次会生成测试存档）
```

## 代码结构

```
client/bedrock/   数据 + 加载 + 骨骼/姿态（不依赖渲染层）
client/render/    关节贴图网格 / 骨骼线框 / 无深度线渲染类型
client/entity/    玩家接入：PlayerRig、皮肤层、物品层、盔甲模型与层、藏掉原版皮肤
mixin/            LivingEntityRenderer 的 model / layers accessor（替换玩家模型与渲染层）
```

坐标系统的完整推导（游戏世界 / 原版模型空间 / Bedrock geo 空间 / 动画空间之间的换算）
见 [docs/animation-engine.md](docs/animation-engine.md) 第 2 节。
