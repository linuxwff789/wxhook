package com.nous.wxhook.xposed.hook

import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object MessageHook {

    private const val TAG = "[wxhook:Msg]"

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val loader = lpparam.classLoader

        // Strategy 1: Hook autogen.events.SendMsgEvent and ReceiveMsgEvent
        hookEventBus(loader)

        // Strategy 2: Hook normsg classes (message handling layer)
        hookNormsg(loader)

        // Strategy 3: Hook storage.MsgInfo if available
        hookMsgInfo(loader)
    }

    private fun hookEventBus(loader: ClassLoader) {
        // Hook SendMsgEvent constructor to capture outgoing messages
        try {
            val sendMsgClass = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.SendMsgEvent", loader
            )
            // Hook all constructors
            for (constructor in sendMsgClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val event = param.thisObject
                            val fields = event.javaClass.declaredFields
                            val data = StringBuilder()
                            data.append("$TAG SEND: ")
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(event)
                                if (value != null) {
                                    val type = field.type.simpleName
                                    when (type) {
                                        "String" -> data.append("${field.name}=\"${value}\" ")
                                        "long", "int", "boolean" -> data.append("${field.name}=$value ")
                                        else -> {
                                            // Try to read nested object fields
                                            try {
                                                val nestedFields = value.javaClass.declaredFields
                                                for (nf in nestedFields) {
                                                    nf.isAccessible = true
                                                    val nv = nf.get(value)
                                                    if (nv != null) {
                                                        val nt = nf.type.simpleName
                                                        when (nt) {
                                                            "String" -> data.append("${field.name}.${nf.name}=\"${String(nv as ByteArray).take(50)}\" ")
                                                            "long", "int", "boolean" -> data.append("${field.name}.${nf.name}=$nv ")
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                            XposedBridge.log(data.toString())
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG SendMsgEvent read error: ${e.message}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG hooked SendMsgEvent constructors")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG SendMsgEvent hook failed: ${e.message}")
        }

        // Hook ReceiveMsgEvent constructor
        try {
            val recvMsgClass = XposedHelpers.findClass(
                "com.tencent.mm.autogen.events.ReceiveMsgEvent", loader
            )
            for (constructor in recvMsgClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val event = param.thisObject
                            val fields = event.javaClass.declaredFields
                            val data = StringBuilder()
                            data.append("$TAG RECV: ")
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(event)
                                if (value != null) {
                                    val type = field.type.simpleName
                                    when (type) {
                                        "String" -> data.append("${field.name}=\"${value}\" ")
                                        "long", "int", "boolean" -> data.append("${field.name}=$value ")
                                        else -> {
                                            try {
                                                val nestedFields = value.javaClass.declaredFields
                                                for (nf in nestedFields) {
                                                    nf.isAccessible = true
                                                    val nv = nf.get(value)
                                                    if (nv != null) {
                                                        val nt = nf.type.simpleName
                                                        when (nt) {
                                                            "String" -> data.append("${field.name}.${nf.name}=\"${String(nv as ByteArray).take(50)}\" ")
                                                            "long", "int", "boolean" -> data.append("${field.name}.${nf.name}=$nv ")
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                            }
                            XposedBridge.log(data.toString())
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG ReceiveMsgEvent read error: ${e.message}")
                        }
                    }
                })
            }
            XposedBridge.log("$TAG hooked ReceiveMsgEvent constructors")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG ReceiveMsgEvent hook failed: ${e.message}")
        }
    }

    private fun hookNormsg(loader: ClassLoader) {
        // Hook normsg.g constructor (message data carrier)
        try {
            val gClass = XposedHelpers.findClass("com.tencent.mm.normsg.g", loader)
            for (constructor in gClass.constructors) {
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val obj = param.thisObject
                            val fields = obj.javaClass.declaredFields
                            val data = StringBuilder()
                            data.append("$TAG normsg.g: ")
                            for (field in fields) {
                                field.isAccessible = true
                                val value = field.get(obj)
                                if (value != null) {
                                    val type = field.type.simpleName
                                    when (type) {
                                        "String" -> data.append("${field.name}=\"${value}\" ")
                                        "long", "int", "boolean" -> data.append("${field.name}=$value ")
                                        "[B" -> {
                                            val bytes = value as ByteArray
                                            data.append("${field.name}=hex(${bytes.take(16).joinToString("") { String.format("%02x", it) }}) ")
                                        }
                                    }
                                }
                            }
                            XposedBridge.log(data.toString())
                        } catch (e: Throwable) {}
                    }
                })
            }
            XposedBridge.log("$TAG hooked normsg.g")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG normsg.g hook failed: ${e.message}")
        }
    }

    private fun hookMsgInfo(loader: ClassLoader) {
        // Try to hook MsgInfo if it exists
        try {
            val msgInfoClass = XposedHelpers.findClass("com.tencent.mm.storage.MsgInfo", loader)
            XposedBridge.log("$TAG found MsgInfo class, hooking insert methods")
            
            for (method in msgInfoClass.declaredMethods) {
                val name = method.name
                if (name.contains("insert", ignoreCase = true) || 
                    name.contains("add", ignoreCase = true) ||
                    name.contains("send", ignoreCase = true)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("$TAG MsgInfo.${name}() called, result=${param.result}")
                        }
                    })
                    XposedBridge.log("$TAG hooked MsgInfo.$name")
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG MsgInfo not found (expected with Tinker)")
        }
    }
}
