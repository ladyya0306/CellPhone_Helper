package com.example.llama

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 步数的数据来源：系统计步器（TYPE_STEP_COUNTER，开机以来的累计步数）。需要「身体活动」运行时权限，只能由用户在弹窗里点允许。
 * 折算成每天多少步的规则在 [StepLogic]；这里只管读传感器和存盘（files/steps.json）。默认关闭。
 */
object StepSource {
    private val LOCK = Any()
    private const val MIN_GAP_MS = 10 * 60_000L

    /** 调试钩子用：假装已授权/有传感器，只为在没授权的手机上验证"记录 → 回答"这条链路；真读传感器仍然需要真权限 */
    @Volatile var debugAssume: Boolean = false

    fun hasPermission(ctx: Context) = debugAssume || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    fun hasSensor(ctx: Context) = debugAssume || ctx.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** 读一次计步器的累计值；没权限、没传感器、读不到都返回 null */
    fun readTotal(ctx: Context, timeoutMs: Long = 2500): Long? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return null
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        val latch = CountDownLatch(1)
        var total: Long? = null
        val thread = HandlerThread("step-read").apply { start() }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { total = e.values[0].toLong(); latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        return try {
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(thread.looper))
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            total
        } catch (_: Exception) { null } finally {
            try { sm.unregisterListener(l) } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "steps.json")

    fun load(ctx: Context): Pair<StepLogic.State, Long> = synchronized(LOCK) {
        val f = file(ctx)
        if (!f.exists()) return StepLogic.State() to 0L
        try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val d = o.optJSONObject("days")
            val days = d?.keys()?.asSequence()?.associateWith { d.getInt(it) } ?: emptyMap()
            StepLogic.State(if (o.has("lastTotal")) o.getLong("lastTotal") else null, o.optString("firstDay", ""), days) to o.optLong("lastSampleAt", 0L)
        } catch (_: Exception) {
            f.renameTo(File(ctx.filesDir, "steps.json.corrupt-${System.currentTimeMillis()}"))
            StepLogic.State() to 0L
        }
    }

    private fun save(ctx: Context, s: StepLogic.State, lastSampleAt: Long) = synchronized(LOCK) {
        val o = JSONObject().put("firstDay", s.firstDay).put("lastSampleAt", lastSampleAt)
        s.lastTotal?.let { o.put("lastTotal", it) }
        o.put("days", JSONObject().also { j -> s.days.forEach { (k, v) -> j.put(k, v) } })
        AtomicFile.write(file(ctx), o.toString())
    }

    /** 采样一次并折算存盘。force=false 时 10 分钟内不重复读。返回是否真的采到了 */
    fun sample(ctx: Context, force: Boolean = false, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!AppSettings(ctx).stepsEnabled) return false
        val (st, last) = load(ctx)
        if (!force && !StepLogic.shouldSample(last, nowMs, MIN_GAP_MS)) return false
        val total = readTotal(ctx) ?: return false
        save(ctx, StepLogic.apply(st, nowMs, total, ZoneId.systemDefault()), nowMs)
        return true
    }

    /** 调试用：直接塞一个采样（模拟某天某个累计值），不读传感器 */
    fun inject(ctx: Context, atMs: Long, total: Long) {
        val (st, last) = load(ctx)
        save(ctx, StepLogic.apply(st, atMs, total, ZoneId.systemDefault()), maxOf(last, atMs))
    }

    fun clear(ctx: Context) = synchronized(LOCK) { file(ctx).delete(); Unit }

    fun summary(ctx: Context, nowMs: Long = System.currentTimeMillis()): StepLogic.Summary? {
        val st = load(ctx).first
        return if (st.lastTotal == null) null else StepLogic.summary(st, nowMs, ZoneId.systemDefault())
    }
}
