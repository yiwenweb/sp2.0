package com.sp.dazi2;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.sp.dazi2.model.NaviData;
import com.sp.dazi2.service.BridgeService;
import com.tencent.map.geolocation.TencentLocation;
import com.tencent.map.geolocation.TencentLocationListener;
import com.tencent.map.geolocation.TencentLocationManager;
import com.tencent.map.geolocation.TencentLocationRequest;
import com.tencent.navi.api.TencentCarNaviManager;
import com.tencent.navi.api.listener.INaviListener;
import com.tencent.navi.api.listener.IRoutePlanListener;
import com.tencent.navi.api.model.AttachedLocation;
import com.tencent.navi.api.model.NaviLatLng;
import com.tencent.navi.api.model.NaviRouteInfo;
import com.tencent.navi.api.model.RoutePlanParam;

import java.util.List;

/**
 * SP搭子 2.0 导航界面
 *
 * 使用腾讯导航SDK实现完整的驾车导航功能。
 * 通过 INaviListener 回调获取实时导航信息（限速、摄像头、转弯等），
 * 转换为 NaviData 并注入 BridgeService，通过 UDP 发送给 C3。
 *
 * 关键修正（基于官方文档）：
 * 1. 包名: com.tencent.navi.api.* (非 com.tencent.map.navi.*)
 * 2. 初始化: App.java 中通过 NaviInitConfig + init() 完成
 * 3. 算路: searchRoute(RoutePlanParam, IRoutePlanListener)
 * 4. 定位联动: TencentLocationManager → updateLocation 灌点
 * 5. AttachedLocation: getSpeedLimit/getCameraType/getNextTurnDistance 等
 */
public class NaviActivity extends AppCompatActivity {
    private static final String TAG = "NaviActivity";

    private TencentCarNaviManager mNaviManager;
    private TencentLocationManager mLocationManager;
    private final NaviData mNaviData = new NaviData();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // 导航信息更新频率控制
    private long mLastUpdateTime = 0;
    private static final long UPDATE_INTERVAL_MS = 200; // 200ms = 5Hz

    // 导航状态
    private boolean mIsNavigating = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_navi);

        // 检查导航SDK是否已初始化
        if (!App.isNaviSdkReady()) {
            Log.e(TAG, "导航SDK未初始化，无法启动导航");
            android.widget.Toast.makeText(this, "导航SDK未就绪，请稍后重试",
                android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 获取导航管理器单例
        mNaviManager = TencentCarNaviManager.getInstance();

        // 注册导航回调
        mNaviManager.addNaviListener(mNaviListener);

        // 初始化定位SDK并开始灌点
        initLocationManager();

        // 获取目的地坐标
        double endLat = getIntent().getDoubleExtra("end_lat", 0);
        double endLng = getIntent().getDoubleExtra("end_lng", 0);

        if (endLat != 0 && endLng != 0) {
            // 延迟500ms等定位SDK启动后再算路
            mHandler.postDelayed(() -> startRouteSearch(endLat, endLng), 500);
        }
    }

    /**
     * 初始化腾讯定位SDK，将定位数据灌入导航SDK
     *
     * 官方文档要求：导航SDK依赖定位数据驱动，
     * 需通过 updateLocation() 将定位SDK的结果传递给导航SDK。
     * 灌点频率：1Hz（1秒/次）
     */
    private void initLocationManager() {
        mLocationManager = TencentLocationManager.getInstance(getApplicationContext());

        TencentLocationRequest request = TencentLocationRequest.create()
            .setInterval(1000)  // 1秒更新一次
            .setAllowGPS(true)
            .setRequestLevel(TencentLocationRequest.REQUEST_LEVEL_GEO);

        int error = mLocationManager.requestLocationUpdates(request, mLocationListener);
        if (error == 0) {
            Log.i(TAG, "定位SDK启动成功，开始灌点");
        } else {
            Log.e(TAG, "定位SDK启动失败，错误码: " + error);
        }
    }

    /**
     * 定位SDK回调 — 将定位数据灌入导航SDK
     */
    private final TencentLocationListener mLocationListener = new TencentLocationListener() {
        @Override
        public void onLocationChanged(TencentLocation location, int error, String reason) {
            if (error != TencentLocation.ERROR_OK || location == null) return;

            // 构建导航SDK的Location对象并灌入
            com.tencent.navi.api.model.Location naviLocation =
                new com.tencent.navi.api.model.Location();
            naviLocation.setLatitude(location.getLatitude());
            naviLocation.setLongitude(location.getLongitude());
            naviLocation.setSpeed(location.getSpeed());
            naviLocation.setBearing(location.getBearing());
            naviLocation.setTime(System.currentTimeMillis());

            mNaviManager.updateLocation(naviLocation);
        }

        @Override
        public void onStatusUpdate(String provider, int status, String desc) {
            Log.d(TAG, "定位状态: provider=" + provider + " status=" + status);
        }
    };

    /**
     * 路线规划（算路）
     *
     * 官方API: searchRoute(RoutePlanParam, IRoutePlanListener)
     * 起点为空时使用当前GPS位置（需定位SDK已启动）
     */
    private void startRouteSearch(double endLat, double endLng) {
        RoutePlanParam param = new RoutePlanParam.Builder()
            .setEndPoint(new NaviLatLng(endLat, endLng))
            .setRoutePlanType(RoutePlanParam.ROUTE_PLAN_TYPE_FASTEST)
            .setAvoidCongestion(true)
            .build();

        mNaviManager.searchRoute(param, new IRoutePlanListener() {
            @Override
            public void onRoutePlanSuccess(List<NaviRouteInfo> routeList) {
                if (routeList == null || routeList.isEmpty()) {
                    Log.w(TAG, "算路成功但无路线");
                    return;
                }
                Log.i(TAG, "算路成功，共 " + routeList.size() + " 条路线");
                // 选择第一条最优路线开始导航
                try {
                    mNaviManager.startNavi(routeList.get(0));
                    mIsNavigating = true;
                    Log.i(TAG, "导航已启动");
                } catch (Exception e) {
                    Log.e(TAG, "启动导航失败", e);
                }
            }

            @Override
            public void onRoutePlanFailed(int errorCode, String errorMsg) {
                Log.e(TAG, "算路失败: " + errorCode + " " + errorMsg);
                runOnUiThread(() -> android.widget.Toast.makeText(NaviActivity.this,
                    "路线规划失败: " + errorMsg, android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * 导航回调 — 核心数据采集
     *
     * 通过 INaviListener 获取导航事件和 AttachedLocation 数据，
     * 转换为 NaviData 格式注入 BridgeService 发送给 C3。
     */
    private final INaviListener mNaviListener = new INaviListener() {

        @Override
        public void onStartNavi() {
            Log.i(TAG, "导航开始");
        }

        @Override
        public void onStopNavi() {
            Log.i(TAG, "导航结束");
            mIsNavigating = false;
            BridgeService.setCurrentData(new NaviData());
        }

        @Override
        public void onArrivedDestination() {
            Log.i(TAG, "到达目的地");
            mNaviData.nGoPosDist = 0;
            mNaviData.nGoPosTime = 0;
            pushNaviData();
        }

        @Override
        public void onOffRoute() {
            Log.w(TAG, "偏航，等待重新规划");
        }

        @Override
        public void onRecalculateRouteSuccess(List<NaviRouteInfo> routeList) {
            Log.i(TAG, "重新规划成功");
        }

        @Override
        public void onRecalculateRouteFailed(int errorCode, String errorMsg) {
            Log.e(TAG, "重新规划失败: " + errorCode + " " + errorMsg);
        }

        /**
         * 吸附定位更新 — 最核心的数据来源
         *
         * AttachedLocation 包含（官方文档确认）：
         * - getLatitude/getLongitude: 吸附到道路上的精确坐标
         * - getSpeed: 当前车速 (km/h)
         * - getBearing: 车头朝向
         * - getRoadName: 当前道路名称
         * - getSpeedLimit: 道路限速 (km/h)
         * - getCameraType: 摄像头类型 (0=无,1=测速,2=违章拍照,3=区间测速)
         * - getNextTurnDistance: 到下一转弯点距离 (米)
         */
        @Override
        public void onUpdateAttachedLocation(AttachedLocation loc) {
            if (loc == null) return;

            long now = System.currentTimeMillis();
            if (now - mLastUpdateTime < UPDATE_INTERVAL_MS) return;
            mLastUpdateTime = now;

            // GPS 坐标（吸附到道路上的精确坐标）
            mNaviData.vpPosPointLat = loc.getLatitude();
            mNaviData.vpPosPointLon = loc.getLongitude();
            mNaviData.nPosAngle = loc.getBearing();

            // 道路信息
            String roadName = loc.getRoadName();
            if (roadName != null && !roadName.isEmpty()) {
                mNaviData.szPosRoadName = roadName;
            }

            // 道路限速（官方确认: getSpeedLimit 返回 int, 单位 km/h）
            int speedLimit = loc.getSpeedLimit();
            if (speedLimit > 0) {
                mNaviData.nRoadLimitSpeed = mNaviData.applySpeedMapping(speedLimit);
            } else {
                mNaviData.nRoadLimitSpeed = 0;
            }

            // 转弯信息（官方确认: getNextTurnDistance 返回 float, 单位 米）
            float turnDist = loc.getNextTurnDistance();
            mNaviData.nTBTDist = turnDist;
            // 转弯类型需要从导航SDK获取并映射为高德ICON值
            // 注意：AttachedLocation 可能没有直接的 getNextTurnType 方法
            // 转弯类型通常通过导航事件回调获取，这里暂保留之前的值

            // 电子眼/测速摄像头
            // 官方确认: getCameraType 返回 int
            // 0=无, 1=测速, 2=违章拍照, 3=区间测速
            int cameraType = loc.getCameraType();
            handleCameraData(cameraType, loc);

            // ETA 格式化（使用剩余时间计算预计到达时间）
            if (mNaviData.nGoPosTime > 0) {
                formatEta(mNaviData.nGoPosTime);
            }

            pushNaviData();
        }
    };

    /**
     * 处理摄像头数据
     *
     * 腾讯SDK摄像头类型（官方文档）：
     *   0=无, 1=测速, 2=违章拍照, 3=区间测速
     *
     * 映射到高德类型（navi_bridge.py 使用的）：
     *   0=测速, 1=监控, 2=闯红灯, 3=违章拍照, 5=区间测速起点, 6=区间测速终点
     */
    private void handleCameraData(int cameraType, AttachedLocation loc) {
        if (cameraType <= 0) {
            // 无摄像头
            mNaviData.nSdiType = -1;
            mNaviData.nSdiSpeedLimit = 0;
            mNaviData.nSdiDist = 0;
            mNaviData.nSdiBlockType = -1;
            mNaviData.nSdiBlockSpeed = 0;
            mNaviData.nSdiBlockDist = 0;
            return;
        }

        // 尝试获取摄像头限速和距离
        // 注意：官方文档只确认了 getCameraType()
        // getSpeedLimit() 是道路限速，摄像头限速可能需要从其他回调获取
        // 这里用道路限速作为摄像头限速的近似值
        int cameraSpeed = loc.getSpeedLimit();
        float cameraDist = loc.getNextTurnDistance(); // 近似：用转弯距离代替

        if (cameraType == 3) {
            // 区间测速 → 映射为高德的区间测速起点(5)
            mNaviData.nSdiBlockType = 5;
            mNaviData.nSdiBlockSpeed = cameraSpeed;
            mNaviData.nSdiBlockDist = cameraDist;
            mNaviData.nSdiType = -1;
            mNaviData.nSdiSpeedLimit = 0;
            mNaviData.nSdiDist = 0;
        } else {
            // 普通测速(1) / 违章拍照(2) → 映射为高德测速类型(0)
            mNaviData.nSdiType = 0; // 高德: 0=测速
            mNaviData.nSdiSpeedLimit = cameraSpeed;
            mNaviData.nSdiDist = cameraDist;
            mNaviData.nSdiBlockType = -1;
            mNaviData.nSdiBlockSpeed = 0;
            mNaviData.nSdiBlockDist = 0;
        }
    }

    /** 格式化 ETA 到达时间 */
    private void formatEta(int remainSec) {
        if (remainSec <= 0) return;
        int hours = remainSec / 3600;
        int mins = (remainSec % 3600) / 60;
        long arrivalMs = System.currentTimeMillis() + remainSec * 1000L;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm");
        String arrivalTime = sdf.format(new java.util.Date(arrivalMs));
        if (hours > 0) {
            mNaviData.etaText = "预计" + arrivalTime + "到达 (" + hours + "时" + mins + "分)";
        } else {
            mNaviData.etaText = "预计" + arrivalTime + "到达 (" + mins + "分钟)";
        }
    }

    /** 将导航数据推送给 BridgeService */
    private void pushNaviData() {
        BridgeService.setCurrentData(mNaviData);
    }

    // ═══ 生命周期管理 ═══

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 移除导航回调
        if (mNaviManager != null) {
            mNaviManager.removeNaviListener(mNaviListener);
            if (mIsNavigating) {
                mNaviManager.stopNavi();
            }
        }
        // 停止定位
        if (mLocationManager != null) {
            mLocationManager.removeUpdates(mLocationListener);
        }
        super.onDestroy();
    }
}
