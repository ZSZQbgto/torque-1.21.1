# Torque 骨骼动画引擎设计（Minecraft 1.21.1 / NeoForge 21.1.265 / Java 21）

> 目标：把 Blockbench / Bedrock 的 `*.geo.json`（几何体）与 `*.animation.json`（动画）
> 以资源包的形式加载，运行时由一个统一的骨骼类驱动，**代码里不写死任何骨骼名、UV、关键帧、动作**。
> 渲染层（关节贴图）、骨骼层（Skeleton/Pose）、实体接入层（玩家 / 盔甲 / 手持物品 / F3 调试）
> 三部分互相解耦。
>
> `C:\Project\torque-template-26.2` 只是旧工程，**除贴图/UV 的结论外不再参考它的实现，也不要修改它**。

---

## 1. 资源包约定（代码零硬编码）

| 内容 | 路径 |
| --- | --- |
| 几何体 | `assets/<namespace>/geometries/**.geo.json`（Bedrock `format_version 1.12.0`） |
| 动画 | `assets/<namespace>/animations/**.animation.json` |
| 绑定 | `assets/<namespace>/rigs/**.json` |

模组默认资源（资源包可同路径覆盖）：

```
assets/torque/geometries/player.geo.json        <- C:\DSHhome\resourse\player.geo.json
assets/torque/animations/player.animation.json  <- C:\DSHhome\resourse\animation\player.animation.json
assets/torque/rigs/player.json
```

加载规则：所有 namespace 下这些目录都会被扫描；几何体按 `description.identifier` 索引
（如 `geometry.default_player`），动画按 `animations` 的键索引（如 `walk` / `run`），
同名时资源包优先级高者覆盖。每次资源重载（F3+T / 换包）重新解析，无需重启。

### `rigs/player.json`

```json
{
  "geometry": "geometry.default_player",
  "animations": {
    "idle":       { "clip": "animation.idle", "clock": "time" },
    "walk":       { "clip": "walk",          "clock": "walk_position" },
    "run":        { "clip": "run",           "clock": "walk_position" },
    "sneak":      { "clip": "animation.sneak" },
    "sneak_walk": { "clip": "animation.sneak_walk", "clock": "walk_position" },
    "fly":        { "clip": "animation.fly" },
    "hover":      { "clip": "animation.hover" }
  },
  "items": { "right": "rightItem", "left": "leftItem" },
  "walkPositionPerCycle": 9.4333,
  "hideVanilla": ["cape"]
}
```

- `geometry`：几何体 identifier。
- `animations`：语义槽位（idle/walk/run/sneak/sneak_walk/fly/hover）→ 动画名；没有的槽位保持静止姿势。
- `clock`：`time`（按秒循环）或 `walk_position`（按行走距离驱动）。缺省 `time`。
- `items`：左右手物品骨骼名（这些骨骼上的占位 cube 不画，改画真实手持物品）。
- `walkPositionPerCycle`：`walk_position` 时钟下，`LivingEntity#walkAnimation.position`
  增加多少 = 一个完整循环（默认 9.4333 ≈ 2π/0.6662，即原版一个步子循环）。
- `hideVanilla`：隐藏的原版渲染层，支持 `cape`、`wings`（1.21.1 对应 `ElytraLayer`）。

---

## 2. 坐标关系（权威定义，代码注释以此为准）

### 2.1 游戏世界坐标

```
+X = 东    +Y = 上    +Z = 南
实体 yaw：0° 朝 +Z(南)，90° 朝 -X(西)，180° 朝 -Z(北)，270° 朝 +X(东)
实体原点 = 脚底中心；前方 = yaw 指向；左右按面向前方时的左右手
```

### 2.2 原版模型空间（`ModelPart` 所在空间，1.21.1 实测）

`1 单位 = 1/16 格`，**Y 向下**，`+Z = 背后`、`-Z = 前方`，`+X = 玩家左侧`、
`-X = 玩家右侧`（原版 `right_arm` 在 x = -5）。模型原点在实体原点**上方 1.501 格**
（`ModelPart` y=0 在颈部/肩高，脚在 y=24 像素）。

`LivingEntityRenderer#render` 对模型的变换（从内到外）：

```
world = entityPos ∘ Ry(180° - bodyRot) ∘ S(0.9375) ∘ S(-1,-1,1) ∘ T(0,-1.501,0) · modelPoint
```

`0.9375` 来自 `PlayerRenderer#scale`。bodyRot=0（朝南）时可核对：

| 模型点 (px) | 世界（相对实体原点） | 含义 |
| --- | --- | --- |
| (0, 24, 0) | (0, ~0, 0) | 脚底 |
| (0, 0, 0) | (0, ~1.407, 0) | 颈部/肩高 |
| (-5, 2, 0) | (-0.293, ~1.29, 0) | 右肩：世界 -X = 玩家右手边 |
| (0, -8, 0) | (0, ~1.876, 0) | 头顶 |

### 2.3 Bedrock 几何体空间（`*.geo.json`）

```
1 单位 = 1 像素 = 1/16 格；+Y 向上；脚在 y = 0（玩家头顶 y = 32）
-Z = 前方（脸在 z=-4 侧），+Z = 背后（披风 z=+2..+3）
-X = 玩家右侧（rightArm 在 x=-5），+X = 玩家左侧
bone 的 pivot、cube 的 origin/size 都是这个空间的绝对坐标（cube 不相对 pivot）
```

### 2.4 geo space ↔ model space（唯一一次换算）

```
位置：  x_model =  x_geo ,  y_model = 24 - y_geo ,  z_model = z_geo
方向：  (dx, dy, dz)_model = (dx, -dy, dz)_geo
欧拉角：(rx, ry, rz)_model = (-rx, ry, -rz)_geo ，两边旋转顺序都是 ZYX（R = Rz·Ry·Rx）
```

校验：geo `head` (0,24,0)→model (0,0,0)=原版 head；`rightArm` (-5,22,0)→(-5,2,0)=原版右臂；
`rightLeg` (±1.9,12,0)→(±1.9,12,0)=原版腿。
**注意 `body`：geo pivot (0,12,0) 在腰部，原版 `body` 部件 pivot 在 (0,0,0)（颈部）**——
所以盔甲模型必须按 geo 层级重搭（§6.2），不能直接套原版 `HumanoidModel` 层级。

### 2.5 Bedrock 动画空间 / 单位

```
rotation：度，骨骼局部空间，ZYX 顺序
position：像素（1/16 格），方向同 geo space
scale   ：倍率，绕骨骼 pivot
时间    ：秒；loop=true 按 animation_length 取模
骨骼局部变换（geo 像素）：
  local = T(pivot + position) · Rz(rz) · Ry(ry) · Rx(rx) · S(scale) · T(-pivot)
  world(bone) = world(parent) · local
```

### 2.6 从 geo space 到世界（玩家接入层）

`RenderLayer#render` 拿到的 PoseStack 已含 §2.2 中除模型根外的全部变换，模型根是单位变换。
接入层只补 geo→model：

```java
poseStack.translate(0.0, 24.0 / 16.0, 0.0);  // 1.5 格
poseStack.scale(1.0F, -1.0F, 1.0F);
// 之后所有坐标用 geo 像素 / 16 提交
```

等价总式（F3 调试、世界空间计算用）：

```
world = entityPos ∘ Ry(180° - bodyRot) ∘ S(0.9375) ∘ T(0, 0.001, 0) ∘ diag(-1, 1, 1) · (geo 像素 / 16)
```

---

## 3. 代码结构

```
com.zszq.torque.client.bedrock      // 纯数据 + 加载 + 骨骼/姿态（不碰渲染/实体）
  FaceUV, BedrockCube, BedrockBone, BedrockGeometry, GeometryLibrary
  AnimationChannel, BoneTimeline, BedrockAnimation, AnimationLibrary
  AnimationClock, RigAnimation, RigDefinition, RigLibrary
  Skeleton, SkeletonPose
com.zszq.torque.client.render       // 只依赖 bedrock + MC 渲染基础类型
  SkeletonMeshRenderer              // “关节贴图类”：几何体 → 带贴图网格
  SkeletonWireframe                 // 骨骼框架（世界空间线段，F3 用）
com.zszq.torque.client.entity       // 本 mod 的玩家接入
  PlayerRig                         // 玩家状态 → 槽位权重 / 时钟
  SkinnedPlayerLayer                // 皮肤几何体（含 F3 线框）
  SkinnedItemLayer                  // 手持物品（rightItem/leftItem 骨骼）
  SkinnedArmorModel / SkinnedArmorLayer   // 盔甲跟随骨骼
  TorquePlayerModel                 // 藏掉原版皮肤部件（保留第一人称手）
  TorqueClient                      // 注册
```

分层规则：`bedrock` 不 import `render`/`entity`、不碰 PoseStack/VertexConsumer；
`render` 不认识玩家/实体；`entity` 负责实体状态、贴图、顺序与注册。

---

## 4. bedrock 层 API 契约（core-dev）

```java
public record FaceUV(float u, float v, float width, float height) {}   // 像素

public record BedrockCube(float[] origin, float[] size, float inflate, boolean mirror,
                          float[] rotation, float[] uv, Map<String, FaceUV> faceUVs) {
    public boolean hasBoxUv();
}

public final class BedrockBone {   // name, parent(可 null), pivot[3], rotation[3](度), cubes
    public String name(); public String parent();
    public float[] pivot(); public float[] rotation(); public List<BedrockCube> cubes();
}

public final class BedrockGeometry {
    public String identifier(); public int textureWidth(); public int textureHeight();
    public List<BedrockBone> bones(); public BedrockBone bone(String name);
}

public final class GeometryLibrary extends SimplePreparableReloadListener<Map<String, BedrockGeometry>> {
    public static final GeometryLibrary INSTANCE;
    public static final String DIRECTORY = "geometries";
    public static BedrockGeometry get(String identifier);
    public static Map<String, BedrockGeometry> all();
}

public final class AnimationChannel {   // 一条通道（rotation/position/scale）
    public boolean isEmpty(); public float lastTime();
    public void sample(float time, float[] out, boolean loop);   // out 长度 3
}
public record BoneTimeline(AnimationChannel rotation, AnimationChannel position, AnimationChannel scale) {
    public static final BoneTimeline EMPTY;
}
public final class BedrockAnimation {
    public String name(); public boolean loop(); public float length();
    public BoneTimeline bone(String name); public float wrap(float time);
    public static Map<String, BedrockAnimation> parseAll(JsonObject root);
}
public final class AnimationLibrary extends SimplePreparableReloadListener<Map<String, BedrockAnimation>> {
    public static final AnimationLibrary INSTANCE;
    public static final String DIRECTORY = "animations";
    public static BedrockAnimation get(String name);
}

public enum AnimationClock { TIME, WALK_POSITION }
public record RigAnimation(String clip, AnimationClock clock) {}
public record RigDefinition(String geometry, Map<String, RigAnimation> animations,
                            String rightItemBone, String leftItemBone,
                            float walkPositionPerCycle, Set<String> hideVanilla) {
    public RigAnimation slot(String slot);
    public static final RigDefinition EMPTY;
}
public final class RigLibrary extends SimplePreparableReloadListener<Map<String, RigDefinition>> {
    public static final RigLibrary INSTANCE;
    public static final String DIRECTORY = "rigs";
    public static final ResourceLocation PLAYER_RIG = ResourceLocation.fromNamespaceAndPath("torque", "player");
    public static RigDefinition player();  public static RigDefinition get(ResourceLocation id);
}

public final class Skeleton {
    public static Skeleton of(BedrockGeometry geometry);
    public BedrockGeometry geometry(); public int boneCount();
    public int indexOf(String name);           // -1
    public BedrockBone bone(int index);        public int parentOf(int index);
    public float[] pivot(int index);           // geo 像素
}

public final class SkeletonPose {
    public SkeletonPose(Skeleton skeleton);
    public Skeleton skeleton();
    public void clear();
    // 每根骨骼：有通道 → acc += w*采样；无通道 → acc += w*静止值(rotation=几何体 rotation,
    // position=0, scale=1)；最后 totalWeight += w
    public void addAnimation(BedrockAnimation animation, float time, float weight);
    public void finish();      // rot=accRot+(1-totalWeight)*rest，pos=accPos，scale=accScale+(1-totalWeight)
                               // 然后按 §2.5 公式重建 worldMatrix
    public float[] rotation(int boneIndex);  public float[] position(int boneIndex);
    public float[] scale(int boneIndex);
    public Matrix4f worldMatrix(int boneIndex);            // geo 空间，单位=格
    public Vector3f worldPivot(int boneIndex, Vector3f dest);
    public Matrix3f normalMatrix(int boneIndex, Matrix3f dest);
}
```

资源加载用 `FileToIdConverter.json(dir)` + `ResourceManager`（1.21.1 `ResourceLocation`），
`prepare()` 在后台线程、返回不可变快照，`apply()` 原子替换；单文件失败只 log。
`AnimationChannel` 支持 linear / catmullrom / step，`post`/`pre`，`scale` 单数字，
键按时间排序，缺 `animation_length` 用最后关键帧时间。

---

## 5. render 层 API 契约（render-dev）

```java
public final class SkeletonMeshRenderer {
    public static void render(VertexConsumer buffer, PoseStack.Pose pose,
                              Skeleton skeleton, SkeletonPose skelPose,
                              int packedLight, int packedOverlay, int color,
                              Set<String> hiddenBones);
}
```

- 顶点：`worldMatrix(bone) * (geo 像素 / 16)` 得到 geo 格坐标，再 `buffer.addVertex(pose, x, y, z)`。
- 法线：`normalMatrix(bone)` 变换后面归一化，`buffer.setNormal(pose, nx, ny, nz)`。
- `buffer.setColor(color).setUv(u, v).setOverlay(packedOverlay).setLight(packedLight)`。
- box uv 按原版 `ModelPart.Cube` 的布局（`u+d` 起 up/down，左侧 d 列 west，中间 w 列 north/south，
  右侧 d 列 east；mirror 反 U、反顶点顺序）；per-face uv 用像素 `uv_size`（负值翻转）。
- `inflate` 每面外扩；cube `rotation` 绕 cube 中心 ZYX 旋转顶点/法线。
- 每面 4 顶点逆时针（从外看）；UV 归一化用 `skeleton.geometry().textureWidth/Height`。
- `hiddenBones` 里的骨骼（cubes）跳过；不创建 RenderType、不读贴图、不认识实体。

```java
public final class SkeletonWireframe {
    public static void emit(VertexConsumer buffer, PoseStack.Pose pose,
                            Skeleton skeleton, SkeletonPose skelPose,
                            Matrix4f geoToWorld, int color);
}
```

- 每个骨骼 pivot 处画点、父 pivot→子 pivot 画线；`geoToWorld` 是 §2.6 的总变换
  （含 geo→model 与实体→世界），顶点 = `geoToWorld * worldPivot`，再 `addVertex(pose,...)`。
- 线用 `RenderType.lines()` 的 VertexConsumer（POSITION_COLOR_NORMAL）：`.setColor(argb)`、
  `.setNormal(pose, 0, 1, 0)`。

---

## 6. 玩家接入（Lead，`entity` 包）

### 6.1 皮肤 / 状态

- `TorquePlayerModel extends PlayerModel<AbstractClientPlayer>`：`setupAnim` 调 super 后把
  `head/hat/body/arms/legs/sleeves/jacket/cloak/ear` 全部 `visible=false`；第一人称
  `PlayerRenderer#renderHand` 会自己把手臂 visible 打开，所以手还在。
- `PlayerRig`：按 `RigLibrary.player()` 取绑定与几何体，按实体状态算权重：
  `move = clamp(水平速度/0.08,0,1)`；`run = clamp((速度-0.23)/0.04,0,1)`（冲刺=1）；
  sneak/fly/hover 按实体状态；**只把已加载到动画的槽位计入权重**，其余留给静止姿势。
  每帧：`clear() → addAnimation(...) → finish()`。时钟：`walk_position` 用
  `entity.walkAnimation.position(partialTick)/walkPositionPerCycle`，`time` 用
  `(entity.tickCount + partialTick)/20`。
- `PlayerRig` 缓存 `Skeleton`（按几何体对象）与每实体 `SkeletonPose`，避免每帧分配。

### 6.2 盔甲（SkinnedArmorLayer + SkinnedArmorModel）

原版 `HumanoidArmorLayer` 用 `copyPropertiesTo` 把玩家部件姿态抄给盔甲模型，而原版 `body`
pivot 在颈部、geo 在腰部，所以自己做一层。盔甲网格按 geo 层级重搭（像素、model 空间）：

```
root
  bone_root (0,24,0)        [root 骨骼动画]
    waist (0,-12,0)         [waist 骨骼动画]
      body_pivot (0,0,0)    [body 骨骼动画]   // 模型 y=12（腰）
        body (-12 相对)      // 胸甲 cube，绝对 y 0..12
        head (-12 相对)      [head 动画]      // 绝对 y 0
          hat
        left_arm (5,-10,0)  [leftArm 动画]   // 绝对 y=2
        right_arm (-5,-10,0)
    left_leg (1.9,-12,0)    [leftLeg 动画]   // 绝对 y=12
    right_leg (-1.9,-12,0)
```

- 两种网格：outer（`CubeDeformation(1.0)`）与 inner（`CubeDeformation(0.5)`，腿 `extend(-0.1)`），
  纹理尺寸 64×32；cube 参数照原版 `HumanoidModel.createMesh` + `HumanoidArmorModel.createBodyLayer`。
- 渲染逻辑照 `HumanoidArmorLayer#renderArmorPiece`（ArmorMaterial 多层 + 染色 + trim + glint），
  换成自己的模型；每帧 `applyPose(skeleton, pose)`（geo→model 换算：`(-rx,ry,-rz)`、`(dx,-dy,dz)`）。
- 用 `event.getContext().getModelManager()` 拿 armor trim atlas；`ClientHooks.getArmorTexture` 取纹理。

### 6.3 手持物品（SkinnedItemLayer）

- 替换 `PlayerItemInHandLayer`；对每只手：取 `ItemStack`，把 PoseStack 放到物品骨骼
  （走一遍骨骼链：`T(pivot+pos)/16 · R · S · T(-pivot)/16`），然后
  `Rx(-90°) · Ry(180°)`，最后 `ItemInHandRenderer#renderItem(entity, stack, THIRD_PERSON_*, left, poseStack, buffer, light)`。
- 占位 cube 由 `hiddenBones` 隐藏。

### 6.4 渲染顺序 / 注册（TorqueClient）

- `AddLayers`：`for (PlayerSkin.Model type : event.getSkins())` → `event.getSkin(type)`；
  `renderer.model = new TorquePlayerModel(...)`（需 AT 放开 `LivingEntityRenderer#model`）；
  在 `layers` 列表（AT 放开）里：index 0 插 `SkinnedPlayerLayer`，把 `HumanoidArmorLayer`
  换成 `SkinnedArmorLayer`，把 `PlayerItemInHandLayer` 换成 `SkinnedItemLayer`；
  `rig.hideVanilla` 含 `cape` 时移除 `CapeLayer`，含 `wings` 时移除 `ElytraLayer`。
- 资源监听：`RegisterClientReloadListenersEvent#registerReloadListener`（mod bus）。
- AT：`src/main/resources/META-INF/accesstransformer.cfg`
  ```
  public net.minecraft.client.renderer.entity.LivingEntityRenderer model
  public net.minecraft.client.renderer.entity.LivingEntityRenderer layers
  ```

### 6.5 F3 骨骼框架

- `SkinnedPlayerLayer` 里：当 `Minecraft.getInstance().getDebugOverlay().showDebugScreen()`
  时，用 `RenderType.lines()` 的 VertexConsumer 调 `SkeletonWireframe.emit(...)`；
  总变换用 §2.6 总式 + 实体位移/朝向（`entity.getPosition(partialTick)`、`yBodyRot` 插值、
  `getRenderOffset` 蹲伏位移）。这样 F3 下每个被渲染的玩家（含第三人称的自己）显示骨骼线框。
- 线框颜色：骨骼金色，root 红色，物品骨骼青色；pivot 处画点（小十字/点）。

---

## 7. 验收

1. `gradlew compileJava` 通过。
2. `gradlew runClient` 进入游戏，第三人称看到几何体皮肤与 walk/run 动作（来自资源包）。
3. F3 打开后显示骨骼线框。
4. 穿盔甲、手持物品跟随骨骼与动画。
5. 资源包覆盖 `assets/torque/animations/player.animation.json`（F3+T）后动作变化，无需改代码。
6. 完成后 `git init` → 强制推送 `git@github.com:ZSZQbgto/torque-1.21.1.git`，提交信息“完全重构”。

---

## 8. 实机验证记录（1.21.1，开发客户端）

- `gradlew compileJava`：BUILD SUCCESSFUL。
- 启动日志：`Loaded 1 bedrock geometries / 3 bedrock animations / 1 bedrock rigs from resource packs`，
  `Torque player rig ready: geometry=geometry.default_player, capeOverridden=true`；
  `LivingEntityRendererAccessor` mixin 正常注入。
- 第三人称（F5）可见几何体皮肤 + 盔甲（自动装备测试：下界合金全套）+ 玩家手持物品；
  F3 下无深度骨骼线框正确套在角色上（金色骨骼 / 红根 / 白关节），关闭 F3 不显示。
- 动画采样验证：站立时 idle 权重 1.0，`rightArm` 欧拉角随 idle 关键帧连续变化
  （如 -8°/-10° → 0°/-4° → +5°/-2°），说明“资源包动画 → SkeletonPose 混合 → 网格渲染”链路正常。
- 资源包覆盖验证：把 `tools/resourcepacks/torque-anim-demo/` 放进 `run/resourcepacks/` 并启用后，
  F3+T 即可看到夸张摆臂/抬腿动作变化（无需改代码）。

### 开发期快捷项

- `build.gradle` 的 client run 配置里曾临时加过 `--quickPlaySingleplayer 'New World'` 方便实机截图，
  已删除，发布版本不会自动进存档。
- 验证用的自动装备数据包在 `run/saves/New World/datapacks/torque-test/`（`run/` 被 .gitignore 忽略，不入库）。
