package com.sp.dazi2;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import com.tencent.map.geolocation.TencentLocationManager;
import com.tencent.navi.api.TencentCarNaviManager;
import com.tencent.navi.api.listener.INaviInitListener;
import com.tencent.navi.api.model.NaviInitConfig;

/**
 * SP搭子 2.0 Application
 *
 * 初始化顺序：
 * 1. 通知渠道（前台服务）
 * 2. 腾讯地图SDK隐私合规
 * 3. 腾讯定位SDK隐私合规
 * 4. 腾讯导航SDK初始化（NaviInitConfig + init回调）
 */
public class App extends Application {
    private static final String TAG = "App";
    public static final String CHANNEL_ID = "sp_dazi2_channel";

    // 导航SDK初始化状态（NaviActivity 启动前需检查）
    private static volatile boolean sNaviSdkReady = false;

    public static boolean isNaviSdkReady() {
        return sNaviSdkReady;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initTencentMapPrivacy();
        initNaviSdk();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "SP搭子2.0服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("导航桥接服务运行中");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /**
     * 腾讯定位SDK 隐私合规初始化
     * 必须在使用定位功能之前调用
     */
    private void initTencentMapPrivacy() {
        TencentLocationManager.setUserAgreePrivacy(true);
    }

    /**
     * 腾讯导航SDK初始化
     * 使用 NaviInitConfig 配置 Key + 上下文，通过 INaviInitListener 回调确认初始化结果
     */
    private void initNaviSdk() {
        // 从 AndroidManifest.xml 的 meta-data 读取 Key
        String appKey = "";
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager()
                .getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                appKey = ai.metaData.getString("TencentMapSDK", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "读取TencentMapSDK Key失败", e);
        }

        NaviInitConfig initConfig = new NaviInitConfig.Builder()
            .setContext(getApplicationContext())
            .setAppKey(appKey)
            .setLogEnable(BuildConfig.DEBUG)
            .setNaviType(NaviInitConfig.NAVI_TYPE_CAR)
            .build();

        TencentCarNaviManager.getInstance().init(initConfig, new INaviInitListener() {
            @Override
            public void onInitSuccess() {
                sNaviSdkReady = true;
                Log.i(TAG, "腾讯导航SDK初始化成功");
            }

            @Override
            public void onInitFailed(int errorCode, String errorMsg) {
                sNaviSdkReady = false;
                Log.e(TAG, "腾讯导航SDK初始化失败: " + errorCode + " - " + errorMsg);
            }
        });
    }
}
