package com.nous.wxhook.db

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * WeChat message XML parser.
 * Parses message content from the content column in the message table.
 */
object MessageParser {

    private const val TAG = "wxhook:Parser"

    // Message types
    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_VOICE = 34
    const val TYPE_CARD = 42
    const val TYPE_VIDEO = 43
    const val TYPE_EMOJI_DYNAMIC = 47
    const val TYPE_LOCATION = 48
    const val TYPE_APP = 49
    const val TYPE_SYSTEM = 10000
    const val TYPE_REVOKE = 10002

    // App message subtypes (type=49)
    const val APP_LINK = 5
    const val APP_FILE = 6
    const val APP_IMAGE_MSG = 8
    const val APP_LOCATION = 13
    const val APP_COLLECTION = 14
    const val APP_MERGE_FORWARD = 19
    const val APP_MINI_PROGRAM = 20
    const val APP_MUSIC = 24
    const val APP_MINI_CARD = 33

    data class ParsedMessage(
        val type: Int,
        val typeDesc: String,
        val content: String?,
        val subType: Int = 0,
        val subTypeDesc: String = "",
        val mediaPath: String? = null,
        val fileName: String? = null,
        val fileSize: Long = 0,
        val title: String? = null,
        val url: String? = null
    )

    /**
     * Parse message content based on type.
     */
    fun parse(type: Int, content: String?, subType: Int = 0): ParsedMessage {
        return when (type) {
            TYPE_TEXT -> ParsedMessage(
                type = type,
                typeDesc = "文本",
                content = content
            )

            TYPE_IMAGE -> parseImage(content)
            TYPE_VOICE -> parseVoice(content)
            TYPE_VIDEO -> parseVideo(content)
            TYPE_APP -> parseApp(content, subType)
            TYPE_SYSTEM -> ParsedMessage(
                type = type,
                typeDesc = "系统消息",
                content = content
            )
            TYPE_REVOKE -> ParsedMessage(
                type = type,
                typeDesc = "撤回消息",
                content = content
            )
            TYPE_CARD -> parseCard(content)
            TYPE_LOCATION -> parseLocation(content)
            TYPE_EMOJI_DYNAMIC -> ParsedMessage(
                type = type,
                typeDesc = "动态表情",
                content = content
            )
            else -> ParsedMessage(
                type = type,
                typeDesc = "未知($type)",
                content = content
            )
        }
    }

    private fun parseImage(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_IMAGE, "图片", null)
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(content))
            var aesKey: String? = null
            var cdnUrl: String? = null
            var length: Long = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "img" -> {
                                aesKey = parser.getAttributeValue(null, "aeskey")
                                cdnUrl = parser.getAttributeValue(null, "cdnbigimgurl")
                                length = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0
                            }
                        }
                    }
                }
                parser.next()
            }

            return ParsedMessage(
                type = TYPE_IMAGE,
                typeDesc = "图片",
                content = content,
                mediaPath = cdnUrl,
                fileSize = length
            )
        } catch (e: Exception) {
            return ParsedMessage(TYPE_IMAGE, "图片", content)
        }
    }

    private fun parseVoice(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_VOICE, "语音", null)
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(content))
            var voiceLength: String? = null
            var voiceFormat: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "voicemsg" -> {
                                voiceLength = parser.getAttributeValue(null, "voicelength")
                                voiceFormat = parser.getAttributeValue(null, "voiceformat")
                            }
                        }
                    }
                }
                parser.next()
            }

            return ParsedMessage(
                type = TYPE_VOICE,
                typeDesc = "语音(${voiceLength}ms)",
                content = content,
                mediaPath = voiceFormat
            )
        } catch (e: Exception) {
            return ParsedMessage(TYPE_VOICE, "语音", content)
        }
    }

    private fun parseVideo(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_VIDEO, "视频", null)
        return ParsedMessage(TYPE_VIDEO, "视频", content)
    }

    private fun parseCard(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_CARD, "名片", null)
        return ParsedMessage(TYPE_CARD, "名片", content)
    }

    private fun parseLocation(content: String?): ParsedMessage {
        if (content == null) return ParsedMessage(TYPE_LOCATION, "位置", null)
        return ParsedMessage(TYPE_LOCATION, "位置", content)
    }

    private fun parseApp(content: String?, subType: Int): ParsedMessage {
        val typeDesc = when (subType) {
            APP_LINK -> "链接"
            APP_FILE -> "文件"
            APP_IMAGE_MSG -> "图文消息"
            APP_LOCATION -> "实时位置"
            APP_COLLECTION -> "收藏"
            APP_MERGE_FORWARD -> "合并转发"
            APP_MINI_PROGRAM -> "小程序"
            APP_MUSIC -> "音乐"
            APP_MINI_CARD -> "小程序卡片"
            else -> "应用消息($subType)"
        }

        if (content == null) return ParsedMessage(TYPE_APP, typeDesc, null, subType)

        // Parse title and URL from XML
        var title: String? = null
        var url: String? = null
        var fileName: String? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(content))
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "appmsg" -> {
                                title = parser.getAttributeValue(null, "title")
                                url = parser.getAttributeValue(null, "url")
                            }
                            "appattach" -> {
                                fileName = parser.getAttributeValue(null, "cdnattachurl")
                            }
                        }
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {}

        return ParsedMessage(
            type = TYPE_APP,
            typeDesc = typeDesc,
            content = content,
            subType = subType,
            subTypeDesc = typeDesc,
            title = title,
            url = url,
            fileName = fileName
        )
    }

    /**
     * Get human-readable type description.
     */
    fun getTypeDesc(type: Int): String = when (type) {
        TYPE_TEXT -> "文本"
        TYPE_IMAGE -> "图片"
        TYPE_VOICE -> "语音"
        TYPE_CARD -> "名片"
        TYPE_VIDEO -> "视频"
        TYPE_EMOJI_DYNAMIC -> "动态表情"
        TYPE_LOCATION -> "位置"
        TYPE_APP -> "应用消息"
        TYPE_SYSTEM -> "系统消息"
        TYPE_REVOKE -> "撤回消息"
        else -> "未知($type)"
    }
}
