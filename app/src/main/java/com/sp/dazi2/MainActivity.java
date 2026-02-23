package com.sp.dazi2;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.sp.dazi2.model.NaviData;
import com.sp.dazi2.service.BridgeService;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * SP搭子 2.0 主界面
 *
 * 功能：
 * 1. C3 连接管理（手动IP / 自动发现）
 * 2. 目的地搜索 → 启动 NaviActivity 导航
 * 3. C3 视频流显示 + HUD 叠加
 * 4. 导航数据状态显示
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity2";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "sp_dazi2_prefs";
    private static final String KEY_C3_IP = "c3_ip";

    // Views
    private EditText etC3Ip, etDestination;
    private Button btnConnect, btnStartStop, btnStartNavi;
    private TextView tvConnectionState, tvNaviStatus;
    private WebView wvVideo;
    private View tvVideoHint;
    private LinearLayout hudOverlay;
    private TextView tvHudSpeed, tvHudCruise, tvHudGear;

    // WebSocket
    private OkHttpClient wsClient;
    private WebSocket carStateWs;
    private boolean wsConnected = false;

    // Service
    private BridgeService bridgeService;
    private boolean serviceBound = false;
    private boolean serviceRunning = false;
    private boolean videoLoaded = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable uiUpdateRunnable;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BridgeService.LocalBinder binder = (BridgeService.LocalBinder) service;
            bridgeService = binder.getService();
            serviceBound = true;
            bridgeService.setStateCallback(new BridgeService.StateCallback() {
                @Override
                public void onStateChanged(BridgeService.ConnectionState state, String c3Ip) {
                    uiHandler.post(() -> {
                        updateConnectionUI(state, c3Ip);
                        if (state == BridgeService.ConnectionState.CONNECTED && c3Ip != null) {
                            loadVideo(c3Ip);
                        }
                    });
                }
                @Override
                public void onDataSent(int count) { }
            });
            updateConnectionUI(bridgeService.getConnectionState(), bridgeService.getC3IpAddress());
            if (bridgeService.getConnectionState() == BridgeService.ConnectionState.CONNECTED) {
                loadVideo(bridgeService.getC3IpAddress());
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            bridgeService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        loadSavedIp();
        requestPermissions();
        // 默认限速映射：120→110
        NaviData.setSpeedMapping(120, 110);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUIUpdate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopUIUpdate();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        disconnectWs();
        if (wvVideo != null) wvVideo.destroy();
        super.onDestroy();
    }

    private void initViews() {
        etC3Ip = findViewById(R.id.et_c3_ip);
        etDestination = findViewById(R.id.et_destination);
        btnConnect = findViewById(R.id.btn_connect);
        btnStartStop = findViewById(R.id.btn_start_stop);
        btnStartNavi = findViewById(R.id.btn_start_navi);
        tvConnectionState = findViewById(R.id.tv_connection_state);
        tvNaviStatus = findViewById(R.id.tv_navi_status);
        wvVideo = findViewById(R.id.wv_video);
        tvVideoHint = findViewById(R.id.tv_video_hint);
        hudOverlay = findViewById(R.id.hud_overlay);
        tvHudSpeed = findViewById(R.id.tv_hud_speed);
        tvHudCruise = findViewById(R.id.tv_hud_cruise);
        tvHudGear = findViewById(R.id.tv_hud_gear);

        // WebView 设置
        WebSettings ws = wvVideo.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        wvVideo.setWebViewClient(new WebViewClient());

        btnConnect.setOnClickListener(v -> onConnectClicked());
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        btnStartNavi.setOnClickListener(v -> onStartNaviClicked());
    }

    private void onConnectClicked() {
        String ip = etC3Ip.getText().toString().trim();
        if (ip.isEmpty()) { Toast.makeText(this, "请输入C3 IP", Toast.LENGTH_SHORT).show(); return; }
        hideKeyboard();
        saveIp(ip);
        if (serviceBound && bridgeService != null) {
            bridgeService.setC3Ip(ip);
            loadVideo(ip);
            Toast.makeText(this, "已设置 C3 IP: " + ip, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "请先启动服务", Toast.LENGTH_SHORT).show();
        }
    }

    private void onStartStopClicked() {
        if (serviceRunning) stopBridgeService(); else startBridgeService();
    }

    /**
     * 启动导航 — 使用腾讯地图 WebService API 搜索 POI
     *
     * 搜索API: https://apis.map.qq.com/ws/place/v1/suggestion
     * 参数: keyword=目的地, key=腾讯地图Key
     * 返回: JSON 包含 data[].location.lat/lng
     */
    private void onStartNaviClicked() {
        String dest = etDestination.getText().toString().trim();
        if (dest.isEmpty()) {
            Toast.makeText(this, "请输入目的地", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();

        if (!App.isNaviSdkReady()) {
            Toast.makeText(this, "导航SDK未就绪，请稍后重试", Toast.LENGTH_SHORT).show();
            return;
        }

        String mapKey = getMapKey();
        if (mapKey.isEmpty() || mapKey.equals("YOUR_TENCENT_MAP_KEY_HERE")) {
            Toast.makeText(this, "请先在AndroidManifest.xml中配置腾讯地图Key",
                Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "正在搜索: " + dest, Toast.LENGTH_SHORT).show();

        // 异步搜索POI
        new Thread(() -> {
            try {
                String url = "https://apis.map.qq.com/ws/place/v1/suggestion/?keyword="
                    + java.net.URLEncoder.encode(dest, "UTF-8")
                    + "&key=" + mapKey;

                okhttp3.Request req = new okhttp3.Request.Builder().url(url).get().build();
                okhttp3.Response resp = new okhttp3.OkHttpClient().newCall(req).execute();
                String body = resp.body() != null ? resp.body().string() : "";

                org.json.JSONObject json = new org.json.JSONObject(body);
                int status = json.optInt("status", -1);
                if (status != 0) {
                    String msg = json.optString("message", "搜索失败");
                    runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                    return;
                }

                org.json.JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    runOnUiThread(() -> Toast.makeText(this, "未找到: " + dest,
                        Toast.LENGTH_SHORT).show());
                    return;
                }

                // 取第一个结果
                org.json.JSONObject first = data.getJSONObject(0);
                String title = first.optString("title", dest);
                org.json.JSONObject location = first.getJSONObject("location");
                double lat = location.getDouble("lat");
                double lng = location.getDouble("lng");

                Log.i(TAG, "POI搜索结果: " + title + " (" + lat + "," + lng + ")");
                runOnUiThread(() -> startNavigation(lat, lng, title));

            } catch (Exception e) {
                Log.e(TAG, "POI搜索异常", e);
                runOnUiThread(() -> Toast.makeText(this, "搜索异常: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 从 AndroidManifest 读取腾讯地图Key */
    private String getMapKey() {
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager()
                .getApplicationInfo(getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                return ai.metaData.getString("TencentMapSDK", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "读取Key失败", e);
        }
        return "";
    }

    /** 启动导航到指定坐标（供POI搜索结果调用） */
    public void startNavigation(double lat, double lng, String name) {
        Intent intent = new Intent(this, NaviActivity.class);
        intent.putExtra("end_lat", lat);
        intent.putExtra("end_lng", lng);
        intent.putExtra("end_name", name);
        startActivity(intent);
    }

    private void startBridgeService() {
        Intent intent = new Intent(this, BridgeService.class);
        String ip = etC3Ip.getText().toString().trim();
        if (!ip.isEmpty()) { intent.putExtra("c3_ip", ip); saveIp(ip); }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        serviceRunning = true;
        btnStartStop.setText("停止服务");
    }

    private void stopBridgeService() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        stopService(new Intent(this, BridgeService.class));
        serviceRunning = false;
        bridgeService = null;
        videoLoaded = false;
        disconnectWs();
        btnStartStop.setText("启动服务");
        tvConnectionState.setText("未启动");
        hudOverlay.setVisibility(View.GONE);
        tvVideoHint.setVisibility(View.VISIBLE);
        wvVideo.loadUrl("about:blank");
    }

    private void loadVideo(String c3Ip) {
        if (c3Ip == null || videoLoaded) return;
        wvVideo.loadUrl("http://" + c3Ip + ":8099?cam=road");
        tvVideoHint.setVisibility(View.GONE);
        hudOverlay.setVisibility(View.VISIBLE);
        videoLoaded = true;
        connectWs(c3Ip);
    }

    private void connectWs(String c3Ip) {
        if (wsConnected) return;
        if (wsClient == null) wsClient = new OkHttpClient();
        Request req = new Request.Builder().url("ws://" + c3Ip + ":7000/ws/carstate").build();
        carStateWs = wsClient.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response resp) { wsConnected = true; }
            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject j = new JSONObject(text);
                    double vEgo = j.optDouble("vEgo", 0);
                    double vSet = j.optDouble("vSetKph", 0);
                    String gear = j.optString("gear", "P");
                    int speedKph = (int) Math.round(vEgo * 3.6);
                    int cruiseKph = (int) Math.round(vSet);
                    uiHandler.post(() -> {
                        tvHudSpeed.setText(String.valueOf(speedKph));
                        tvHudCruise.setText(cruiseKph > 0 ? String.valueOf(cruiseKph) : "--");
                        tvHudGear.setText(gear);
                    });
                } catch (Exception e) { }
            }
            @Override
            public void onFailure(WebSocket ws, Throwable t, Response resp) {
                wsConnected = false;
                uiHandler.postDelayed(() -> {
                    if (serviceBound && bridgeService != null) {
                        String ip = bridgeService.getC3IpAddress();
                        if (ip != null) connectWs(ip);
                    }
                }, 5000);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) { wsConnected = false; }
        });
    }

    private void disconnectWs() {
        wsConnected = false;
        if (carStateWs != null) { carStateWs.cancel(); carStateWs = null; }
    }

    private void updateConnectionUI(BridgeService.ConnectionState state, String ip) {
        switch (state) {
            case SEARCHING:
                tvConnectionState.setText("搜索C3中...");
                tvConnectionState.setTextColor(0xFFFFB74D);
                break;
            case CONNECTED:
                tvConnectionState.setText("已连接 " + (ip != null ? ip : ""));
                tvConnectionState.setTextColor(0xFF00E5A0);
                break;
            case DISCONNECTED:
                tvConnectionState.setText("已断开");
                tvConnectionState.setTextColor(0xFFFF5252);
                break;
        }
    }

    private void startUIUpdate() {
        uiUpdateRunnable = new Runnable() {
            @Override public void run() {
                updateNaviStatus();
                uiHandler.postDelayed(this, 1000);
            }
        };
        uiHandler.post(uiUpdateRunnable);
    }

    private void stopUIUpdate() {
        if (uiUpdateRunnable != null) uiHandler.removeCallbacks(uiUpdateRunnable);
    }

    private void updateNaviStatus() {
        NaviData data = BridgeService.getCurrentData();
        if (data.szPosRoadName != null && !data.szPosRoadName.isEmpty()) {
            String status = data.szPosRoadName;
            if (data.nRoadLimitSpeed > 0) status += " | 限速" + data.nRoadLimitSpeed + "km/h";
            if (data.nGoPosDist > 0) {
                String dist = data.nGoPosDist >= 1000
                    ? String.format("%.1fkm", data.nGoPosDist / 1000.0)
                    : data.nGoPosDist + "m";
                status += " | 剩余" + dist;
            }
            tvNaviStatus.setText(status);
            tvNaviStatus.setTextColor(0xFF00E5A0);
        } else {
            tvNaviStatus.setText("等待导航数据...");
            tvNaviStatus.setTextColor(0x66FFFFFF);
        }
    }

    // ═══ 权限和存储 ═══

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void saveIp(String ip) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_C3_IP, ip).apply();
    }

    private void loadSavedIp() {
        String ip = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_C3_IP, "");
        if (!ip.isEmpty()) etC3Ip.setText(ip);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }
}
