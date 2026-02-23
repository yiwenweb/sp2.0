package com.sp.dazi2.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.sp.dazi2.App;
import com.sp.dazi2.MainActivity;
import com.sp.dazi2.model.NaviData;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 桥接前台服务 (2.0)
 *
 * 功能：
 * 1. 监听 UDP 7705 端口，接收 C3 设备广播（自动发现）
 * 2. 每 200ms 通过 UDP 7706 向 C3 发送导航 JSON 数据
 * 3. 管理连接状态
 *
 * 与 1.0 的区别：数据来源从 AmapNaviReceiver 改为 NaviActivity 的 SDK 回调，
 * 通过 setCurrentData() 注入。UDP 协议和 JSON 格式完全不变。
 */
public class BridgeService extends Service {
    private static final String TAG = "BridgeService2";
    private static final int NOTIFICATION_ID = 2;
    private static final int DISCOVERY_PORT = 7705;
    private static final int DATA_PORT = 7706;
    private static final long SEND_INTERVAL = 200;

    public enum ConnectionState { SEARCHING, CONNECTED, DISCONNECTED }

    public interface StateCallback {
        void onStateChanged(ConnectionState state, String c3Ip);
        void onDataSent(int packetCount);
    }

    private final IBinder binder = new LocalBinder();
    private StateCallback stateCallback;

    private volatile boolean running = false;
    private volatile String c3IpAddress = null;
    private volatile ConnectionState connectionState = ConnectionState.SEARCHING;
    private int packetCount = 0;

    // 导航数据（由 NaviActivity 回调写入）
    private static volatile NaviData sCurrentData = new NaviData();

    private Thread discoveryThread;
    private Timer sendTimer;
    private DatagramSocket sendSocket;

    public class LocalBinder extends Binder {
        public BridgeService getService() { return BridgeService.this; }
    }

    /** NaviActivity 调用此方法注入最新导航数据 */
    public static void setCurrentData(NaviData data) {
        if (data != null) sCurrentData = data;
    }

    public static NaviData getCurrentData() { return sCurrentData; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("c3_ip")) {
            String ip = intent.getStringExtra("c3_ip");
            if (ip != null && !ip.isEmpty()) {
                c3IpAddress = ip;
                setConnectionState(ConnectionState.CONNECTED);
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification("SP搭子2.0运行中"));
        startBridge();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBridge();
        super.onDestroy();
    }

    public void setStateCallback(StateCallback cb) { this.stateCallback = cb; }
    public ConnectionState getConnectionState() { return connectionState; }
    public String getC3IpAddress() { return c3IpAddress; }
    public int getPacketCount() { return packetCount; }

    public void setC3Ip(String ip) {
        if (ip != null && !ip.isEmpty()) {
            c3IpAddress = ip;
            setConnectionState(ConnectionState.CONNECTED);
        }
    }

    private void startBridge() {
        if (running) return;
        running = true;

        discoveryThread = new Thread(this::discoveryLoop, "C3-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        sendTimer = new Timer("DataSender", true);
        sendTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() { sendNaviData(); }
        }, 1000, SEND_INTERVAL);
    }

    private void stopBridge() {
        running = false;
        if (sendTimer != null) { sendTimer.cancel(); sendTimer = null; }
        if (sendSocket != null && !sendSocket.isClosed()) sendSocket.close();
        if (discoveryThread != null) discoveryThread.interrupt();
    }

    private void discoveryLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setSoTimeout(5000);
            socket.setReuseAddress(true);
            byte[] buf = new byte[1024];
            while (running) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    String senderIp = pkt.getAddress().getHostAddress();
                    if (c3IpAddress == null || !c3IpAddress.equals(senderIp)) {
                        c3IpAddress = senderIp;
                        setConnectionState(ConnectionState.CONNECTED);
                    }
                } catch (SocketTimeoutException e) {
                    if (c3IpAddress == null) setConnectionState(ConnectionState.SEARCHING);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "发现线程异常", e);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
        }
    }

    private void sendNaviData() {
        if (c3IpAddress == null) return;
        try {
            JSONObject json = sCurrentData.toJson();
            byte[] bytes = json.toString().getBytes("UTF-8");
            if (sendSocket == null || sendSocket.isClosed()) sendSocket = new DatagramSocket();
            InetAddress addr = InetAddress.getByName(c3IpAddress);
            DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, addr, DATA_PORT);
            sendSocket.send(pkt);
            packetCount++;
            if (stateCallback != null) stateCallback.onDataSent(packetCount);
        } catch (Exception e) {
            Log.e(TAG, "发送数据失败", e);
            setConnectionState(ConnectionState.DISCONNECTED);
        }
    }

    private void setConnectionState(ConnectionState state) {
        if (connectionState != state) {
            connectionState = state;
            if (stateCallback != null) stateCallback.onStateChanged(state, c3IpAddress);
        }
    }

    private Notification buildNotification(String text) {
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("SP搭子2.0")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
