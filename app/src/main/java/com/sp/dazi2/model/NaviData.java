package com.sp.dazi2.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 导航数据模型 — 与 navi_bridge.py 的 JSON 格式完全对应
 *
 * 2.0 版本：数据来源从高德广播改为腾讯导航SDK回调，
 * 但 JSON 字段名保持与 1.0 完全一致，C3 端无需任何修改。
 */
public class NaviData {
    // 道路限速 km/h
    public int nRoadLimitSpeed = 0;

    // 测速摄像头
    public int nSdiType = -1;          // 测速类型
    public int nSdiSpeedLimit = 0;     // 测速限速 km/h
    public double nSdiDist = 0;        // 到测速点距离 m
    public int nSdiBlockType = -1;     // 区间测速类型
    public int nSdiBlockSpeed = 0;     // 区间测速限速 km/h
    public double nSdiBlockDist = 0;   // 区间测速距离 m

    // GPS
    public double vpPosPointLat = 0;   // 纬度
    public double vpPosPointLon = 0;   // 经度
    public float nPosAngle = 0;        // 航向角

    // 道路信息
    public String szPosRoadName = "";  // 道路名称
    public int roadcate = 0;           // 道路类型

    // 转弯 (TBT)
    public double nTBTDist = 0;        // 到转弯点距离 m
    public int nTBTTurnType = 0;       // 转弯类型 (映射为高德ICON值)

    // 目的地
    public int nGoPosDist = 0;         // 到目的地距离 m
    public int nGoPosTime = 0;         // 到目的地时间 s

    // 交通灯
    public int nTrafficLight = 0;      // 0=无, 1=红, 2=绿, 3=黄
    public int nTrafficLightDist = 0;  // 到红绿灯距离 m
    public int nTrafficLightSec = 0;   // 倒计时秒数

    // 服务区/收费站
    public String sapaName = "";
    public int sapaDist = -1;
    public int sapaType = -1;
    public String nextSapaName = "";
    public int nextSapaDist = -1;
    public int nextSapaType = -1;

    // ETA 到达时间
    public String etaText = "";

    // 路况拥堵
    public int tmcSlowDist = 0;
    public int tmcJamDist = 0;
    public int tmcBlockDist = 0;

    // 连续转弯预告
    public int nextNextTurnIcon = 0;
    public String nextNextRoadName = "";

    // 自定义限速映射
    private static final java.util.Map<Integer, Integer> sSpeedMap =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static int sOriginalSpeed = 0;

    public static void setSpeedMapping(int originalKph, int targetKph) {
        if (targetKph > 0 && targetKph != originalKph) {
            sSpeedMap.put(originalKph, targetKph);
        } else {
            sSpeedMap.remove(originalKph);
        }
    }

    public static java.util.Map<Integer, Integer> getSpeedMappings() {
        return new java.util.HashMap<>(sSpeedMap);
    }

    public static int getOriginalSpeed() { return sOriginalSpeed; }

    /** 应用限速映射（120→110 等） */
    public int applySpeedMapping(int speedKph) {
        sOriginalSpeed = speedKph;
        if (speedKph <= 0 || sSpeedMap.isEmpty()) return speedKph;
        Integer mapped = sSpeedMap.get(speedKph);
        return mapped != null ? mapped : speedKph;
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("nRoadLimitSpeed", nRoadLimitSpeed);
            j.put("nSdiType", nSdiType);
            j.put("nSdiSpeedLimit", nSdiSpeedLimit);
            j.put("nSdiDist", nSdiDist);
            j.put("nSdiBlockType", nSdiBlockType);
            j.put("nSdiBlockSpeed", nSdiBlockSpeed);
            j.put("nSdiBlockDist", nSdiBlockDist);
            j.put("vpPosPointLat", vpPosPointLat);
            j.put("vpPosPointLon", vpPosPointLon);
            j.put("nPosAngle", nPosAngle);
            j.put("szPosRoadName", szPosRoadName);
            j.put("roadcate", roadcate);
            j.put("nTBTDist", nTBTDist);
            j.put("nTBTTurnType", nTBTTurnType);
            j.put("nGoPosDist", nGoPosDist);
            j.put("nGoPosTime", nGoPosTime);
            j.put("nTrafficLight", nTrafficLight);
            j.put("nTrafficLightSec", nTrafficLightSec);
            j.put("sapaName", sapaName);
            j.put("sapaDist", sapaDist);
            j.put("sapaType", sapaType);
            j.put("nextSapaName", nextSapaName);
            j.put("nextSapaDist", nextSapaDist);
            j.put("nextSapaType", nextSapaType);
            j.put("etaText", etaText);
            j.put("tmcSlowDist", tmcSlowDist);
            j.put("tmcJamDist", tmcJamDist);
            j.put("tmcBlockDist", tmcBlockDist);
            j.put("nextNextTurnIcon", nextNextTurnIcon);
            j.put("nextNextRoadName", nextNextRoadName);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return j;
    }
}
