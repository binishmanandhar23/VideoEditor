package com.binish.videoeditor.utils

import android.content.res.Resources

object AppUtils {
    fun Double.toIntDp() = this / Resources.getSystem().displayMetrics.density
}