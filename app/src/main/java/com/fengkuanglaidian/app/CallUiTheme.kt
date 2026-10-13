package com.fengkuanglaidian.app

/**
 * 来电界面的「皮肤」定义。
 *
 * 真假的关键在于视觉是否对得上机型，所以把差异全部收进这里：
 * 换一套主题 = 换一个 CallUiTheme 实现，Activity 逻辑完全复用。
 */
data class CallUiTheme(
    /** 内部标识 */
    val id: String,
    /** 界面上显示的名称（给用户选皮肤用） */
    val displayName: String,
    /** 背景色 */
    val backgroundColor: Int,
    /** 主文字颜色 */
    val primaryTextColor: Int,
    /** 次要文字颜色（如"来电"提示） */
    val secondaryTextColor: Int,
    /** 接听按钮颜色 */
    val acceptColor: Int,
    /** 挂断按钮颜色 */
    val declineColor: Int,
    /** 接听区文案 */
    val acceptLabel: String,
    /** 挂断区文案 */
    val declineLabel: String,
    /** true = 滑动接听（iOS 风格），false = 点击按钮（多数安卓风格） */
    val slideToAnswer: Boolean,
    /** 来电提示语，显示在名字上方 */
    val incomingLabel: String
)

object CallThemes {

    /** iOS 风格：深色模糊背景 + 滑动接听 */
    val IOS = CallUiTheme(
        id = "ios",
        displayName = "iOS 风格",
        backgroundColor = 0xFF1C1C1E.toInt(),
        primaryTextColor = 0xFFFFFFFF.toInt(),
        secondaryTextColor = 0xFF9A9AA0.toInt(),
        acceptColor = 0xFF34C759.toInt(),
        declineColor = 0xFFFF3B30.toInt(),
        acceptLabel = "滑动来接听",
        declineLabel = "提醒我",
        slideToAnswer = true,
        incomingLabel = "iPhone 来电"
    )

    /** 安卓原生风格：浅色背景 + 圆形接听/挂断按钮 */
    val ANDROID = CallUiTheme(
        id = "android",
        displayName = "安卓风格",
        backgroundColor = 0xFF0B1B2B.toInt(),
        primaryTextColor = 0xFFFFFFFF.toInt(),
        secondaryTextColor = 0xFF9DB2C6.toInt(),
        acceptColor = 0xFF2ECC71.toInt(),
        declineColor = 0xFFE74C3C.toInt(),
        acceptLabel = "接听",
        declineLabel = "挂断",
        slideToAnswer = false,
        incomingLabel = "来电"
    )

    /** 深色简约风格 */
    val DARK = CallUiTheme(
        id = "dark",
        displayName = "深色简约",
        backgroundColor = 0xFF101014.toInt(),
        primaryTextColor = 0xFFF5F5F7.toInt(),
        secondaryTextColor = 0xFF8E8E93.toInt(),
        acceptColor = 0xFF30D158.toInt(),
        declineColor = 0xFFFF453A.toInt(),
        acceptLabel = "接听",
        declineLabel = "拒接",
        slideToAnswer = false,
        incomingLabel = "来电"
    )

    val ALL = listOf(IOS, ANDROID, DARK)

    fun byId(id: String?): CallUiTheme = ALL.firstOrNull { it.id == id } ?: ANDROID
}
