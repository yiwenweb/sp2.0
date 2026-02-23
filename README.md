# SP搭子 2.0 — 腾讯导航SDK内置版

## 与 1.0 的核心区别

| 对比项 | 1.0 (sp/) | 2.0 (sp2.0/) |
|--------|-----------|--------------|
| 导航数据来源 | 高德地图外部广播 | 腾讯导航SDK内置 |
| 依赖外部App | 需要安装高德地图并开启导航 | 不需要，自带导航功能 |
| 导航界面 | 无（只是数据桥接） | 内置全屏驾车导航界面 |
| 目的地搜索 | 无（在高德中操作） | 内置POI搜索（腾讯WebService API） |
| 定位方式 | 依赖高德广播中的GPS | 腾讯定位SDK + 导航SDK灌点 |
| C3通信协议 | UDP 7706 JSON | UDP 7706 JSON（完全一致） |
| C3端代码 | navi_bridge.py | 不需要改动，协议兼容 |

## 架构

```
用户输入目的地 → POI搜索(腾讯WebService API) → 获取坐标
    → NaviActivity 启动导航(腾讯导航SDK)
    → INaviListener.onUpdateAttachedLocation 回调
    → NaviData.toJson() → BridgeService → UDP 7706 → C3
    → navi_bridge.py → liveMapDataSP → SLC 限速控制
```

## 项目结构

```
sp2.0/
├── build.gradle                          # 根构建文件
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/
├── README.md                             # 本文件
└── app/
    ├── build.gradle                      # 依赖：地图SDK + 定位SDK + 导航SDK + OkHttp
    └── src/main/
        ├── AndroidManifest.xml           # 权限、组件、腾讯地图Key
        ├── java/com/sp/dazi2/
        │   ├── App.java                  # Application：通知渠道 + 隐私合规 + 导航SDK初始化
        │   ├── MainActivity.java         # 主界面：C3连接 + POI搜索 + 视频流 + HUD
        │   ├── NaviActivity.java         # 导航界面：算路 + 导航 + 定位灌点 + 数据采集
        │   ├── model/
        │   │   └── NaviData.java         # 数据模型（JSON字段与1.0完全一致）
        │   └── service/
        │       └── BridgeService.java    # UDP桥接服务（与1.0逻辑一致，数据源改为SDK回调）
        └── res/
            ├── layout/activity_main.xml  # 主界面布局
            ├── layout/activity_navi.xml  # 导航界面布局
            ├── values/themes.xml
            ├── values/strings.xml
            ├── values/colors.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

## 腾讯SDK依赖

```groovy
// 地图SDK
implementation 'com.tencent.map:tencent-map-vector-sdk:6.8.0'
// 定位SDK
implementation 'com.tencent.map.geolocation:TencentLocationSdk-openplatform:7.5.4'
// 导航SDK（新版拆分为 core + tts）
implementation 'com.tencent.map:tencent-map-nav-sdk-core:6.3.0'
implementation 'com.tencent.map:tencent-map-nav-sdk-tts:6.7.0'
```

## 腾讯导航SDK关键API（官方文档确认）

### 初始化流程

```
App.onCreate()
  → TencentMapInitializer.setAgreePrivacy(true)     // 地图SDK隐私合规
  → TencentLocationManager.setUserAgreePrivacy(true) // 定位SDK隐私合规
  → NaviInitConfig.Builder()                         // 导航SDK配置
      .setContext(appContext)
      .setAppKey(key)
      .setNaviType(NAVI_TYPE_CAR)
      .build()
  → TencentCarNaviManager.getInstance().init(config, listener)
```

### 核心类和包名

| 类名 | 包名 | 用途 |
|------|------|------|
| TencentCarNaviManager | com.tencent.navi.api | 导航管理器（单例） |
| NaviInitConfig | com.tencent.navi.api.model | 初始化配置 |
| INaviInitListener | com.tencent.navi.api.listener | 初始化回调 |
| INaviListener | com.tencent.navi.api.listener | 导航事件回调 |
| IRoutePlanListener | com.tencent.navi.api.listener | 算路回调 |
| AttachedLocation | com.tencent.navi.api.model | 吸附定位数据 |
| RoutePlanParam | com.tencent.navi.api.model | 算路参数 |
| NaviLatLng | com.tencent.navi.api.model | 经纬度坐标 |
| NaviRouteInfo | com.tencent.navi.api.model | 路线信息 |
| Location | com.tencent.navi.api.model | 定位灌点数据 |
| TencentLocationManager | com.tencent.map.geolocation | 定位SDK管理器 |
| TencentLocationListener | com.tencent.map.geolocation | 定位回调 |

### AttachedLocation 方法（官方确认）

| 方法 | 返回值 | 说明 |
|------|--------|------|
| getLatitude() | double | 纬度（吸附到道路） |
| getLongitude() | double | 经度（吸附到道路） |
| getSpeed() | float | 当前车速 km/h |
| getBearing() | float | 车头朝向（0=正北） |
| getRoadName() | String | 当前道路名称 |
| getSpeedLimit() | int | 道路限速 km/h |
| getCameraType() | int | 摄像头类型（0=无,1=测速,2=违章拍照,3=区间测速） |
| getNextTurnDistance() | float | 到下一转弯点距离（米） |

### 算路方法

```java
RoutePlanParam param = new RoutePlanParam.Builder()
    .setStartPoint(new NaviLatLng(lat, lng))  // 可选，不设则用当前GPS
    .setEndPoint(new NaviLatLng(lat, lng))    // 必填
    .setRoutePlanType(ROUTE_PLAN_TYPE_FASTEST) // 0=最快,1=最短,2=经济,3=不走高速
    .setAvoidCongestion(true)
    .build();

naviManager.searchRoute(param, new IRoutePlanListener() {
    onRoutePlanSuccess(List<NaviRouteInfo> routeList) → naviManager.startNavi(routeList.get(0))
    onRoutePlanFailed(int errorCode, String errorMsg)
});
```

### 定位灌点

```java
// 定位SDK回调中：
com.tencent.navi.api.model.Location naviLoc = new Location();
naviLoc.setLatitude(tencentLocation.getLatitude());
naviLoc.setLongitude(tencentLocation.getLongitude());
naviLoc.setSpeed(tencentLocation.getSpeed());
naviLoc.setBearing(tencentLocation.getBearing());
naviLoc.setTime(System.currentTimeMillis());
TencentCarNaviManager.getInstance().updateLocation(naviLoc);
```

## 数据流转映射

### 腾讯SDK → NaviData → navi_bridge.py

| 腾讯SDK数据 | NaviData字段 | navi_bridge.py用途 |
|-------------|-------------|-------------------|
| AttachedLocation.getSpeedLimit() | nRoadLimitSpeed | speedLimit → SLC道路限速 |
| AttachedLocation.getCameraType() | nSdiType/nSdiBlockType | speedLimitAhead → 测速摄像头减速 |
| AttachedLocation.getSpeedLimit() | nSdiSpeedLimit/nSdiBlockSpeed | 摄像头限速（用道路限速近似） |
| AttachedLocation.getNextTurnDistance() | nTBTDist | turnSpeedLimitEndDistance → 弯道减速 |
| 转弯类型（待确认具体API） | nTBTTurnType | turnSpeedLimit → 弯道建议速度 |
| AttachedLocation.getLatitude/Longitude() | vpPosPointLat/Lon | LastGPSPosition → GPS坐标 |
| AttachedLocation.getRoadName() | szPosRoadName | currentRoadName → 道路名称显示 |

### 摄像头类型映射

| 腾讯SDK | 含义 | → 高德类型 | navi_bridge.py处理 |
|---------|------|-----------|-------------------|
| 0 | 无 | -1 | 不处理 |
| 1 | 测速 | 0 | SDI_CAMERA_TYPES → speedLimitAhead |
| 2 | 违章拍照 | 0 | SDI_CAMERA_TYPES → speedLimitAhead |
| 3 | 区间测速 | 5 | SDI_SECTION_TYPES → 区间限速 |

## 使用前准备

### 1. 申请腾讯地图Key
1. 注册 https://lbs.qq.com
2. 控制台 → 应用管理 → 创建应用
3. 添加Key → 勾选「导航SDK」权限
4. 填写应用包名: `com.sp.dazi2`
5. 填写SHA1签名（debug签名: `keytool -list -v -keystore ~/.android/debug.keystore`）

### 2. 配置Key
Key 已内置在 `AndroidManifest.xml` 中，无需手动配置。

### 3. 编译运行
用 Android Studio 打开 `sp2.0/` 目录，Sync Gradle 后 Build APK。

## 已知限制和待确认项

1. **转弯类型API** — AttachedLocation 官方文档确认了 `getNextTurnDistance()`，但转弯类型（左转/右转/掉头等）的具体获取方法待实际编译确认。可能需要通过其他回调或 NaviRouteInfo 获取。
2. **摄像头距离** — 官方文档确认了 `getCameraType()`，但摄像头距离和摄像头限速的具体方法名待确认。目前用道路限速和转弯距离近似。
3. **剩余距离/时间** — `getRemainDistance()`/`getRemainTime()` 方法名待实际SDK确认，可能在 NaviRouteInfo 或其他回调中。
4. **导航SDK权限** — 导航SDK需要联系腾讯小助手开通权限，普通开发者Key可能无法直接使用导航功能。
5. **新版SDK包名** — 导航SDK core:6.3.0 + tts:6.7.0 的实际包名可能与旧版 5.4.6.1 不同，需编译验证。

## C3端配置（不需要改动）

C3端的 `navi_bridge.py` 和 `longitudinal_planner.py` 不需要任何修改，因为 UDP JSON 协议完全一致。

SLC参数设置：
```bash
# SSH到C3
ssh comma@<C3_IP>
# 设置参数
echo -n "1" > /data/params/d/EnableSlc
echo -n "1" > /data/params/d/SpeedLimitControlPolicy  # 1=map_data_only
echo -n "1" > /data/params/d/SpeedLimitOffsetType      # 1=fixed
echo -n "0" > /data/params/d/SpeedLimitValueOffset      # 0=不偏移
```
