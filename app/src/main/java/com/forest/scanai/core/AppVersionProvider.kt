package com.forest.scanai.core

import android.content.Context

class AppVersionProvider(private val context: Context) {
    val versionName: String
        get() = packageInfo?.versionName ?: "0.0.0"

    val versionCode: Long
        get() = packageInfo?.longVersionCode ?: 0L

    val displayVersion: String
        get() = "v$versionName ($versionCode)"

    private val packageInfo by lazy {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
}
