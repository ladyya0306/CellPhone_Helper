package com.example.llama

import java.util.Locale

/**
 * 天气（可选，默认关）：数据来源、解析、中文说法、穿衣带伞建议。纯 Kotlin，可单测；取数在 [WeatherClient]。
 *
 * 数据来源的优先级（见 WeatherClient）：
 *   1. 手机自带天气应用已经取好的数据（vivo 天气的内容提供者）——不联网、不发任何坐标，和用户桌面上看到的一致；
 *   2. 用户在设置里打开了「联网查天气」时，用 Open-Meteo（免密钥）。请求里只有取整到 0.1°（约 10 公里）的城市坐标，没有任何设备或用户标识。
 * 所有数字、天气说法、建议都由程序按规则给出；模型不参与，也就不会编天气。
 */
object Weather {

    /** rainProb：-1 = 数据里没有（手机天气常见），不能当成 0；text 非空时优先用它（手机天气给的是中文，如「晴转多云」） */
    data class Day(val code: Int, val max: Double, val min: Double, val rainProb: Int, val text: String = "")

    /** source：vivo / open-meteo，写进回答里让用户知道数据哪来的 */
    data class Snapshot(
        val city: String, val fetchedAtMs: Long, val nowTemp: Double, val nowCode: Int, val windKmh: Double,
        val days: List<Day>, val source: String = "open-meteo",
        val feelsLike: Int? = null, val aqi: Int? = null, val aqiLevel: String = "",
    )

    private const val OFFLINE_MAX_AGE_MS = 12 * 3_600_000L

    fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f".format(Locale.US, WeatherCities.round1(lat), WeatherCities.round1(lon)) +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"

    private fun num(x: Any?): Double? = (x as? Number)?.toDouble()

    fun parse(json: String, city: String, fetchedAtMs: Long, source: String = "open-meteo"): Snapshot? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val cur = root["current"] as? Map<*, *> ?: return null
        val temp = num(cur["temperature_2m"]) ?: return null
        val daily = root["daily"] as? Map<*, *> ?: return null
        val codes = daily["weather_code"] as? List<*> ?: return null
        val maxs = daily["temperature_2m_max"] as? List<*> ?: return null
        val mins = daily["temperature_2m_min"] as? List<*> ?: return null
        val rains = daily["precipitation_probability_max"] as? List<*>
        val n = minOf(codes.size, maxs.size, mins.size)
        if (n == 0) return null
        val days = (0 until n).mapNotNull { i ->
            val c = num(codes[i])?.toInt() ?: return@mapNotNull null
            Day(c, num(maxs[i]) ?: return@mapNotNull null, num(mins[i]) ?: return@mapNotNull null, num(rains?.getOrNull(i))?.toInt() ?: 0)
        }
        if (days.isEmpty()) return null
        return Snapshot(city, fetchedAtMs, temp, num(cur["weather_code"])?.toInt() ?: days[0].code, num(cur["wind_speed_10m"]) ?: 0.0, days, source)
    }

    /** WMO 天气码 → 中文 */
    fun describeCode(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "晴间多云"; 2 -> "多云"; 3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"; 56, 57 -> "冻毛毛雨"
        61 -> "小雨"; 63 -> "中雨"; 65 -> "大雨"; 66, 67 -> "冻雨"
        71 -> "小雪"; 73 -> "中雪"; 75 -> "大雪"; 77 -> "米雪"
        80 -> "阵雨"; 81 -> "较强阵雨"; 82 -> "强阵雨"; 85, 86 -> "阵雪"
        95 -> "雷暴"; 96, 99 -> "雷暴伴冰雹"
        else -> "天气不明"
    }

    /** 穿衣、带伞、防暑等建议：纯规则，最多两条，没什么要说的就不说 */
    fun advice(d: Day, windKmh: Double): List<String> {
        val out = ArrayList<String>()
        val t = d.text
        if (d.code in 95..99 || t.contains("雷")) out.add("有雷暴，尽量别在外面久留")
        when {
            d.rainProb >= 60 -> out.add("记得带伞")
            d.rainProb >= 30 -> out.add("可能有雨，带把折叠伞更稳")
            d.rainProb < 0 && Regex("大雨|暴雨").containsMatchIn(t) -> out.add("记得带伞")
            d.rainProb < 0 && t.contains("雨") -> out.add("可能有雨，带把折叠伞更稳")
        }
        if (d.code in 71..77 || d.code == 85 || d.code == 86 || t.contains("雪")) out.add("可能有雪，路面注意防滑保暖")
        if (d.max >= 33) out.add("天气炎热，注意防暑、多喝水")
        if (d.min <= 5) out.add("早晚冷，穿厚外套")
        if (d.max - d.min >= 10) out.add("温差大，带件外套")
        if (windKmh >= 40) out.add("风大，注意安全")
        return out.take(2)
    }

    private fun dayLabel(i: Int) = when (i) { 0 -> "今天"; 1 -> "明天"; 2 -> "后天"; else -> "第${i + 1}天" }

    private fun ageNote(s: Snapshot, nowMs: Long): String {
        val mins = (nowMs - s.fetchedAtMs) / 60_000
        return when {
            mins < 10 -> ""
            mins < 60 -> "（$mins 分钟前的数据）"
            else -> "（${mins / 60} 小时前的数据）"
        }
    }

    fun answer(s: Snapshot, dayIdx: Int, nowMs: Long): String {
        val label = dayLabel(dayIdx)
        val d = s.days.getOrNull(dayIdx) ?: return "${s.city}$label 的预报我这里没有（只有 ${s.days.size} 天的数据）。"
        val sb = StringBuilder("${s.city}$label：${if (d.text.isNotBlank()) d.text else describeCode(d.code)}，")
        if (dayIdx == 0) {
            sb.append("现在 ${Math.round(s.nowTemp)}℃")
            s.feelsLike?.let { sb.append("（体感 $it℃）") }
            sb.append("，")
        }
        sb.append("${Math.round(d.min)}–${Math.round(d.max)}℃")
        if (d.rainProb >= 0) sb.append("，降水概率 ${d.rainProb}%")
        if (dayIdx == 0 && s.windKmh >= 1) sb.append("，风 ${Math.round(s.windKmh)} 公里/时")
        if (dayIdx == 0 && s.aqi != null) sb.append("，空气 ${s.aqiLevel.ifBlank { "—" }}（AQI ${s.aqi}）")
        sb.append("。")
        advice(d, if (dayIdx == 0 && s.windKmh >= 0) s.windKmh else 0.0).takeIf { it.isNotEmpty() }?.let { sb.append("建议：").append(it.joinToString("；")).append("。") }
        sb.append(ageNote(s, nowMs))
        if (s.source == "手机天气") sb.append("（数据来自手机自带天气）")
        return sb.toString()
    }

    /** 问「要带伞吗 / 会下雨吗」时先给结论，再给数据；其余问法直接给数据 */
    fun answerFor(s: Snapshot, question: String, nowMs: Long): String {
        val idx = dayOf(question)
        val base = answer(s, idx, nowMs)
        val d = s.days.getOrNull(idx) ?: return base
        val asksRain = Regex("伞|下雨|下不下|会不会下|雨").containsMatchIn(question)
        if (!asksRain) return base
        val wet = d.rainProb >= 60 || d.text.contains("雨") && d.rainProb < 30 || d.code in 51..67 || d.code in 80..82 || d.code in 95..99
        val maybe = d.rainProb in 30..59
        val asksUmbrella = question.contains("伞")
        val verdict = when {
            wet -> if (asksUmbrella) "要带伞。" else "会下雨，记得带伞。"
            maybe -> "有可能下雨（${d.rainProb}%），带把折叠伞更稳。"
            else -> if (asksUmbrella) "不用带伞。" else "应该不会下雨。"
        }
        return verdict + base
    }

    fun isFresh(s: Snapshot, nowMs: Long, ttlMs: Long): Boolean = nowMs - s.fetchedAtMs <= ttlMs
    fun sameCity(s: Snapshot, city: String): Boolean = s.city == city
    fun usableWhenOffline(s: Snapshot, nowMs: Long): Boolean = nowMs - s.fetchedAtMs <= OFFLINE_MAX_AGE_MS

    /** 用户问的是哪一天：明天→1，后天→2，其余（今天、没说）→0 */
    fun dayOf(text: String): Int = when {
        text.contains("后天") -> 2
        Regex("明天|明日|明早|明晚").containsMatchIn(text) -> 1
        else -> 0
    }
}

/** 城市表：只存城市级坐标（取整到 0.1°）。不用定位权限，用户在设置里选一个城市即可 */
object WeatherCities {
    data class City(val name: String, val lat: Double, val lon: Double)

    val ALL: List<City> = listOf(
        City("北京", 39.9, 116.4), City("上海", 31.2, 121.5), City("广州", 23.1, 113.3), City("深圳", 22.5, 114.1),
        City("杭州", 30.3, 120.2), City("成都", 30.7, 104.1), City("重庆", 29.6, 106.5), City("武汉", 30.6, 114.3),
        City("西安", 34.3, 108.9), City("南京", 32.1, 118.8), City("天津", 39.1, 117.2), City("苏州", 31.3, 120.6),
        City("长沙", 28.2, 113.0), City("郑州", 34.8, 113.7), City("青岛", 36.1, 120.4), City("厦门", 24.5, 118.1),
        City("合肥", 31.8, 117.2), City("宁波", 29.9, 121.6), City("福州", 26.1, 119.3), City("济南", 36.7, 117.0),
        City("昆明", 25.0, 102.7), City("大连", 38.9, 121.6), City("沈阳", 41.8, 123.4), City("哈尔滨", 45.8, 126.6),
        City("无锡", 31.6, 120.3), City("佛山", 23.0, 113.1), City("东莞", 23.0, 113.7), City("珠海", 22.3, 113.6),
        City("南昌", 28.7, 115.9), City("贵阳", 26.6, 106.7), City("南宁", 22.8, 108.4), City("太原", 37.9, 112.5),
        City("石家庄", 38.0, 114.5), City("兰州", 36.1, 103.8), City("乌鲁木齐", 43.8, 87.6), City("海口", 20.0, 110.3),
        City("三亚", 18.3, 109.5), City("拉萨", 29.7, 91.1), City("呼和浩特", 40.8, 111.7), City("银川", 38.5, 106.3),
        City("西宁", 36.6, 101.8),
    )

    fun find(name: String): City? {
        val n = name.trim().removeSuffix("市")
        return ALL.firstOrNull { it.name == n }
    }

    /** 取整到 0.1°（约 10 公里）：发出去的坐标只到城市级 */
    fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0
}

/** 极简 JSON 解析（只为解析天气响应，纯 Kotlin，JVM 自检里也能用）。出错一律返回 null，不抛异常 */
object MiniJson {
    fun parse(s: String): Any? = try {
        val p = P(s); p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != s.length) null else v
    } catch (_: Exception) { null }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(depth: Int): Any? {
            if (depth > 20) throw IllegalStateException("too deep")
            ws()
            return when {
                i >= s.length -> throw IllegalStateException("eof")
                s[i] == '{' -> obj(depth)
                s[i] == '[' -> arr(depth)
                s[i] == '"' -> str()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> numb()
            }
        }
        fun obj(d: Int): Map<String, Any?> {
            i++; ws(); val m = LinkedHashMap<String, Any?>()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); expect(':'); m[k] = value(d + 1); ws()
                if (s[i] == ',') { i++; continue }
                expect('}'); return m
            }
        }
        fun arr(d: Int): List<Any?> {
            i++; ws(); val l = ArrayList<Any?>()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value(d + 1)); ws()
                if (s[i] == ',') { i++; continue }
                expect(']'); return l
            }
        }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c"); i++ }
        fun str(): String {
            expect('"'); val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun numb(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (st == i) throw IllegalStateException("bad value")
            return s.substring(st, i).toDouble()
        }
    }
}

/**
 * 手机自带天气应用（vivo 天气）已经取好的数据 → [Weather.Snapshot]。纯 Kotlin：调用方把内容提供者的行转成 Map 传进来，方便单测。
 * 表：weathermessage（逐日预报）、hourdata（逐小时气温）、cityAQI（空气质量）。字段缺失一律按"未知"处理，不编数。
 */
object VivoWeather {
    private val HOUR_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun snapshot(preferredCity: String?, rows: List<Map<String, String>>, hours: List<Map<String, String>>, aqiRows: List<Map<String, String>>, nowMs: Long): Weather.Snapshot? {
        val city = preferredCity?.takeIf { it.isNotBlank() } ?: rows.firstOrNull()?.get("city") ?: return null
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        val today = "%04d%02d%02d".format(now.year, now.monthValue, now.dayOfMonth)
        val mine = rows.filter { it["city"] == city && (it["date"] ?: "") >= today }.sortedBy { it["date"] }
        val days = mine.mapNotNull { r ->
            val lo = r["low"]?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val hi = r["high"]?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            Weather.Day(0, hi, lo, r["prob"]?.trim()?.removeSuffix("%")?.toIntOrNull() ?: -1, (r["condition"] ?: "").replace("*", "转"))
        }
        if (days.isEmpty()) return null
        val hourTemps = hours.filter { it["city"] == city }.mapNotNull { h ->
            val t = try { java.time.LocalDateTime.parse(h["time"] ?: return@mapNotNull null, HOUR_FMT) } catch (_: Exception) { return@mapNotNull null }
            val temp = h["temp"]?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            t to temp
        }.sortedBy { it.first }
        val nowTemp = hourTemps.lastOrNull { !it.first.isAfter(now) }?.second ?: hourTemps.firstOrNull()?.second ?: ((days[0].max + days[0].min) / 2)
        val feels = mine.firstOrNull()?.get("feelslike")?.trim()?.toIntOrNull()
        val aqi = aqiRows.firstOrNull { it["city"] == city }
        return Weather.Snapshot(city, nowMs, nowTemp, 0, -1.0, days, "手机天气", feels, aqi?.get("aqi")?.trim()?.toIntOrNull(), aqi?.get("level")?.trim() ?: "")
    }
}
