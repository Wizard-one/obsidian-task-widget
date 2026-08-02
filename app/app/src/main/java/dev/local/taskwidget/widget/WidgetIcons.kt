package dev.local.taskwidget.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/** 日历类 widget 单次渲染的任务上限(非集合,受 RemoteViews ~1MB Binder 限制) */
internal const val WIDGET_MAX_ITEMS = 20

/** 次要图标色,跟随系统明暗(与 @color/widget_text_dim 保持一致) */
internal fun dimIconColor(context: Context): Int {
    val night = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return if (night) 0xFFAAAAAA.toInt() else 0xFF666666.toInt()
}

/**
 * 把矢量图标栅格化成位图再设置到 RemoteViews。
 * RemoteViews 对 VectorDrawable 的 android:src 支持不稳(HyperOS 会报 can't load widget),
 * 位图必定可跨进程显示。
 */
internal fun setIcon(views: RemoteViews, viewId: Int, context: Context, resId: Int, sizeDp: Int, color: Int) {
    iconBitmap(context, resId, sizeDp, color)?.let { views.setImageViewBitmap(viewId, it) }
}

internal fun iconBitmap(context: Context, resId: Int, sizeDp: Int, color: Int): Bitmap? = try {
    val d = ContextCompat.getDrawable(context, resId)?.mutate()
    if (d == null) null else {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        DrawableCompat.setTint(d, color)
        d.setBounds(0, 0, px, px)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bmp))
        bmp
    }
} catch (_: Exception) {
    null
}
