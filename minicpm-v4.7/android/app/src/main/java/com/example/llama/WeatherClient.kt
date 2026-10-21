package com.example.llama

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 取天气数据（规则和说法在 [Weather]，这里只管"从哪来"）。来源优先级：
 *   1. 手机自带天气应用的数据（vivo 天气内容提供者）：本机读取，不联网、不发任何坐标，和桌面上看到的一致；
 *   2. 备用：用户在设置里打开「联网查天气」后，用 Open-Meteo（只发取整到 0.1° 的城市坐标）。联网默认关闭。
 * 任何一步失败都返回 null 并说明原因，调用方如实告诉用户，不会编数据。
 */
object WeatherClient {

    data class Result(val snapshot: Weather.Snapshot?, val note: String)

    private const val AUTH = "content://com.vivo.weather.provider/"
    const val DEFAULT_TTL_MS = 2 * 3_600_000L

    /** 调试钩子用：直接给一段 Open-Meteo 格式的响应，免得测试依赖网络 */
    @Volatile var fakeBody: String? = null

    private fun rows(ctx: Context, table: String): List<Map<String, String>> = try {
        ctx.contentResolver.query(Uri.parse(AUTH + table), null, null, null, "_id ASC")?.use { c ->
            val out = ArrayList<Map<String, String>>()
            val names = c.columnNames
            while (c.moveToNext()) {
                val m = HashMap<String, String>()
                names.forEachIndexed { i, n -> if (!c.isNull(i)) try { m[n] = c.getString(i) } catch (_: Exception) {} }
                out.add(m)
            }
            out
        } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** 手机自带天气的数据；没有这个应用、或读不到 → null */
    fun readSystem(ctx: Context, preferredCity: String?, nowMs: Long = System.currentTimeMillis()): Weather.Snapshot? = try {
        VivoWeather.snapshot(preferredCity, rows(ctx, "weathermessage"), rows(ctx, "hourdata"), rows(ctx, "cityAQI"), nowMs)
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- 联网备用
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "weather_net.json")

    private fun readCache(ctx: Context, city: String): Weather.Snapshot? = try {
        val f = cacheFile(ctx)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            Weather.parse(o.getString("raw"), o.getString("city"), o.getLong("fetchedAt"))?.takeIf { Weather.sameCity(it, city) }
        }
    } catch (_: Exception) { null }

    private fun fetchNet(ctx: Context, city: WeatherCities.City, nowMs: Long): Weather.Snapshot? {
        val body = fakeBody ?: try {
            val c = URL(Weather.url(city.lat, city.lon)).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            try { if (c.responseCode != 200) return null; c.inputStream.use { it.readBytes().take(200_000).toByteArray().toString(Charsets.UTF_8) } } finally { c.disconnect() }
        } catch (_: Exception) { return null }
        val snap = Weather.parse(body, city.name, nowMs) ?: return null
        try { cacheFile(ctx).writeText(JSONObject().put("city", city.name).put("fetchedAt", nowMs).put("raw", body).toString(), Charsets.UTF_8) } catch (_: Exception) {}
        return snap
    }

    /**
     * 取一份天气。[allowNetwork] = false 时（心跳）只读本机数据和还新鲜的联网缓存，绝不发请求。
     */
    fun get(ctx: Context, allowNetwork: Boolean = true, nowMs: Long = System.currentTimeMillis()): Result {
        val st = AppSettings(ctx)
        val city = st.weatherCity
        if (st.weatherUseSystem) {
            readSystem(ctx, city.takeIf { it.isNotBlank() }, nowMs)?.let { return Result(it, "") }
        }
        if (!st.weatherNet) {
            return Result(null, if (st.weatherUseSystem) (if (city.isNotBlank()) "手机自带天气里没有你选的城市「$city」（设置里选的城市要和天气应用里的一致，或者清空它）。" else "手机自带天气里没有可用的数据（可能没装天气应用，或还没刷新过）。想让我联网查，可以在设置里打开「联网查天气」。")
            else "天气功能没打开。可以在设置里选用「手机自带天气的数据」，或打开「联网查天气」。")
        }
        val c = WeatherCities.find(city) ?: return Result(null, "联网查天气需要先在设置里选一个城市。")
        val ttl = st.weatherTtlOverrideSec.takeIf { it > 0 }?.times(1000L) ?: DEFAULT_TTL_MS
        readCache(ctx, c.name)?.takeIf { Weather.isFresh(it, nowMs, ttl) }?.let { return Result(it, "") }
        if (allowNetwork) fetchNet(ctx, c, nowMs)?.let { return Result(it, "") }
        // 联网失败：拿不太旧的缓存顶上，并让回答里标明是几小时前的数据
        readCache(ctx, c.name)?.takeIf { Weather.usableWhenOffline(it, nowMs) }?.let { return Result(it, "") }
        return Result(null, "没能连上天气服务，也没有可用的旧数据。")
    }
}
