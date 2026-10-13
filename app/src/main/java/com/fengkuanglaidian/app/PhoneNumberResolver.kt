package com.fengkuanglaidian.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 号码归属地与运营商解析（**完全离线**，不联网、不上传号码）。
 *
 * 数据来源：`dannyhu926/phone_location`（2026 年 4 月，516,217 条号段记录，
 * 覆盖 31 省 / 358 市 / 19 类运营商），已转换成 assets 里的紧凑二进制：
 *
 *  - `phone_index.bin`：按 7 位号段升序排列的定长记录，每条 12 字节
 *      u32 key（前 7 位号码）
 *      u16 provinceId / u16 cityId / u16 ispId / u16 保留
 *  - `phone_strings.bin`：字符串池，先 u32 总数，再逐条 [u16 长度 + UTF-8]
 *      顺序为 全部省 → 全部市 → 全部运营商，
 *      所以三个 id 各自从 0 开始编号，取字符串时要加对应类别的起始偏移。
 *
 * 查询方式：对 7 位号段做二分查找。516k 条数据只占 6 MB，全量在内存里，
 * 一次查询约 19 次比较，主线程也够快。
 */
object PhoneNumberResolver {

    private const val INDEX_ASSET = "phone_index.bin"
    private const val STRINGS_ASSET = "phone_strings.bin"
    private const val RECORD_SIZE = 12

    data class PhoneInfo(
        /** 原始输入 */
        val raw: String,
        /** 用于显示的格式化号码 */
        val display: String,
        /** 运营商名，如「中国移动」；未知为 null */
        val carrier: String?,
        /** 归属地，如「山东青岛」；未知为 null */
        val region: String?,
        /** 副标题：如「山东青岛 中国移动」 */
        val subtitle: String,
        /** 是否 11 位手机号 */
        val isMobile: Boolean
    )

    @Volatile
    private var index: ByteBuffer? = null

    @Volatile
    private var strings: Array<String> = emptyArray()

    @Volatile
    private var provinceCount = 0

    @Volatile
    private var cityCount = 0

    @Volatile
    private var recordCount = 0

    private val loading = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)

    /** 是否已加载完成（可用于界面提示）。 */
    val isReady: Boolean get() = ready.get()

    /**
     * 预加载。建议在 Application 或设置页后台线程调用一次，
     * 免得第一次查号时在 UI 线程上读 6 MB。
     */
    fun preload(context: Context) {
        if (ready.get() || !loading.compareAndSet(false, true)) return
        try {
            val app = context.applicationContext

            val stringsBytes = app.assets.open(STRINGS_ASSET).use { it.readBytes() }
            val sb = ByteBuffer.wrap(stringsBytes).order(ByteOrder.LITTLE_ENDIAN)
            val total = sb.int
            val list = ArrayList<String>(total)
            for (i in 0 until total) {
                val len = sb.short.toInt() and 0xFFFF
                val buf = ByteArray(len)
                sb.get(buf)
                list.add(String(buf, Charsets.UTF_8))
            }
            strings = list.toTypedArray()
            provinceCount = PROVINCE_COUNT
            cityCount = total - PROVINCE_COUNT - ISP_COUNT

            val indexBytes = app.assets.open(INDEX_ASSET).use { it.readBytes() }
            recordCount = indexBytes.size / RECORD_SIZE
            index = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)

            ready.set(true)
            android.util.Log.i(
                "PhoneNumberResolver",
                "preload ok: records=$recordCount strings=${list.size} cities=$cityCount"
            )
        } catch (e: Exception) {
            // 资源缺失时保持未就绪，查询会退化成"未知"
            ready.set(false)
            android.util.Log.e("PhoneNumberResolver", "preload failed", e)
        } finally {
            loading.set(false)
        }
    }

    /** 解析输入，返回界面上要显示的全部信息。 */
    fun resolve(context: Context?, input: String): PhoneInfo {
        if (context != null && !ready.get()) preload(context)

        val trimmed = input.trim()
        val digits = trimmed.filter { it.isDigit() }

        if (digits.length == 11 && digits.startsWith("1")) {
            val hit = lookup(digits)
            return PhoneInfo(
                raw = trimmed,
                display = formatMobile(digits),
                carrier = hit?.third,
                region = hit?.let { it.first + it.second },
                subtitle = buildSubtitle(hit),
                isMobile = true
            )
        }

        if (digits.length in 10..12 && !digits.startsWith("1")) {
            val region = lookupLandline(digits)
            return PhoneInfo(
                raw = trimmed,
                display = formatLandline(digits),
                carrier = null,
                region = region,
                subtitle = if (region != null) "$region 固定电话" else "中国 固定电话",
                isMobile = false
            )
        }

        return PhoneInfo(
            raw = trimmed,
            display = trimmed.ifEmpty { "未知号码" },
            carrier = null,
            region = null,
            subtitle = when {
                digits.startsWith("400") || digits.startsWith("800") -> "中国 企业服务号码"
                digits.length in 3..6 -> "中国 服务号码"
                else -> "中国"
            },
            isMobile = false
        )
    }

    /** 便捷重载：不带 Context 时要求已经 preload 过。 */
    fun resolve(input: String): PhoneInfo = resolve(null, input)

    /** 二分查找 7 位号段，命中返回 (省, 市, 运营商)。 */
    private fun lookup(mobile11: String): Triple<String, String, String>? {
        val buf = index ?: return null
        if (mobile11.length < 7 || recordCount == 0) return null
        val key = mobile11.substring(0, 7).toIntOrNull() ?: return null

        var lo = 0
        var hi = recordCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val base = mid * RECORD_SIZE
            val value = buf.getInt(base)
            when {
                value == key -> return readRecord(base)
                value < key -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    private fun readRecord(base: Int): Triple<String, String, String> {
        val buf = index ?: return Triple("", "", "")
        val provinceId = buf.getShort(base + 4).toInt() and 0xFFFF
        val cityId = buf.getShort(base + 6).toInt() and 0xFFFF
        val ispId = buf.getShort(base + 8).toInt() and 0xFFFF
        val pool = strings

        val province = pool.getOrNull(provinceId) ?: ""
        val city = pool.getOrNull(provinceCount + cityId) ?: ""
        val isp = pool.getOrNull(provinceCount + cityCount + ispId) ?: ""
        return Triple(province, city, isp)
    }

    private fun buildSubtitle(hit: Triple<String, String, String>?): String {
        if (hit == null) return "中国 未知地区"
        val (province, city, isp) = hit
        val place = when {
            city.isNotEmpty() && province.isNotEmpty() -> province + city
            city.isNotEmpty() -> city
            province.isNotEmpty() -> province
            else -> "中国"
        }
        return if (isp.isNotEmpty()) "$place $isp" else place
    }

    private fun lookupLandline(digits: String): String? = when {
        digits.startsWith("10") -> "北京"
        digits.startsWith("21") -> "上海"
        digits.startsWith("22") -> "天津"
        digits.startsWith("23") -> "重庆"
        digits.startsWith("531") -> "山东济南"
        digits.startsWith("532") -> "山东青岛"
        digits.startsWith("20") -> "广东广州"
        digits.startsWith("755") -> "广东深圳"
        digits.startsWith("25") -> "江苏南京"
        digits.startsWith("571") -> "浙江杭州"
        digits.startsWith("28") -> "四川成都"
        digits.startsWith("27") -> "湖北武汉"
        digits.startsWith("29") -> "陕西西安"
        digits.startsWith("371") -> "河南郑州"
        digits.startsWith("731") -> "湖南长沙"
        digits.startsWith("591") -> "福建福州"
        digits.startsWith("592") -> "福建厦门"
        digits.startsWith("24") -> "辽宁沈阳"
        else -> null
    }

    /** 11 位手机号按 3-4-4 分段显示，和原生拨号器一致。 */
    fun formatMobile(digits: String): String {
        if (digits.length != 11) return digits
        return "${digits.substring(0, 3)} ${digits.substring(3, 7)} ${digits.substring(7)}"
    }

    private fun formatLandline(digits: String): String {
        if (digits.length <= 4) return digits
        return digits.substring(0, digits.length - 4) + " " + digits.substring(digits.length - 4)
    }

    // 数据集固定为 31 省 / 19 类运营商，其余全是市。
    private const val PROVINCE_COUNT = 31
    private const val ISP_COUNT = 19
}
