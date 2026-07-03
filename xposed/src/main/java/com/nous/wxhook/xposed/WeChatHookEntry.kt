package com.nous.wxhook.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class WeChatHookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        XposedBridge.log("[wxhook] loaded in WeChat ${lpparam.appInfo?.versionName}")

        // Hook 密钥捕获
        KeyCaptureHook.hook(lpparam)

        // Hook 消息拦截
        MessageHook.hook(lpparam)

        // Hook 防撤回
        AntiRecallHook.hook(lpparam)
    }
}
