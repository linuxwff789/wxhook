package com.nous.wxhook.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object SetupManager {

    private val BINS = listOf("git", "rclone", "sqlcipher",
        "libz.so.1", "libcrypto.so.3", "libedit.so", "libncursesw.so.6")
    private val EXEC = listOf("git", "rclone", "sqlcipher")

    fun setup(ctx: Context): Boolean {
        val dir = File(ctx.filesDir, "bin"); if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, ".setup_done"); if (marker.exists()) return true
        return try {
            for (name in BINS) {
                val dst = File(dir, name)
                ctx.assets.open("bin/$name").use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 65536) } }
                if (name in EXEC) dst.setExecutable(true, false)
                dst.setReadable(true, false)
            }
            marker.writeText("ok"); true
        } catch (e: Exception) { false }
    }

    fun git(ctx: Context) = File(ctx.filesDir, "bin/git").absolutePath
    fun rclone(ctx: Context) = File(ctx.filesDir, "bin/rclone").absolutePath
    fun sqlcipher(ctx: Context) = File(ctx.filesDir, "bin/sqlcipher").absolutePath
    fun libDir(ctx: Context) = File(ctx.filesDir, "bin").absolutePath
}
