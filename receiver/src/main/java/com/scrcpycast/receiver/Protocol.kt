package com.scrcpycast.receiver

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object Protocol {
    const val MAGIC = 0x53435250
    const val DEFAULT_PORT = 37280

    const val CODEC_H264 = 0x68_32_36_34
    const val CODEC_H265 = 0x68_32_36_35

    const val TYPE_CONFIG = 0
    const val TYPE_VIDEO_FRAME = 1
    const val TYPE_CODEC_CONFIG = 2
    const val TYPE_KEEPALIVE = 3

    const val HEADER_SIZE = 28

    data class FrameHeader(
        val magic: Int,
        val `type`: Int,
        val codec: Int,
        val width: Int,
        val height: Int,
        val pts: Long,
        val flags: Int
    )

    data class Config(
        val port: Int = DEFAULT_PORT,
        val codec: Int = CODEC_H264,
        val width: Int = 1920,
        val height: Int = 1080,
        val bitRate: Int = 8_000_000,
        val frameRate: Int = 30
    )

    fun writeHeader(out: OutputStream, type: Int, codec: Int, width: Int, height: Int, pts: Long, frameSize: Int) {
        val buf = ByteBuffer.allocate(HEADER_SIZE + 4)
        buf.putInt(MAGIC)
        buf.putInt(type)
        buf.putInt(codec)
        buf.putInt(width)
        buf.putInt(height)
        buf.putLong(pts)
        buf.putInt(frameSize)
        out.write(buf.array())
        out.flush()
    }

    fun readHeader(`in`: InputStream): Pair<FrameHeader, Int>? {
        val headerBuf = ByteArray(HEADER_SIZE + 4)
        var offset = 0
        while (offset < headerBuf.size) {
            val n = `in`.read(headerBuf, offset, headerBuf.size - offset)
            if (n < 0) return null
            offset += n
        }
        val buf = ByteBuffer.wrap(headerBuf)
        val magic = buf.getInt()
        if (magic != MAGIC) return null
        val type = buf.getInt()
        val codec = buf.getInt()
        val width = buf.getInt()
        val height = buf.getInt()
        val pts = buf.getLong()
        val frameSize = buf.getInt()
        return FrameHeader(magic, type, codec, width, height, pts, 0) to frameSize
    }

    fun writeConfig(out: OutputStream, config: Config) {
        writeHeader(out, TYPE_CONFIG, config.codec, config.width, config.height, 0, 0)
        val configBuf = ByteBuffer.allocate(8)
        configBuf.putInt(config.bitRate)
        configBuf.putInt(config.frameRate)
        out.write(configBuf.array())
        out.flush()
    }

    fun readConfig(`in`: InputStream): Config? {
        val pair = readHeader(`in`) ?: return null
        val (header, _) = pair
        if (header.type != TYPE_CONFIG) return null
        val configBuf = ByteArray(8)
        if (`in`.read(configBuf) != 8) return null
        val buf = ByteBuffer.wrap(configBuf)
        return Config(
            codec = header.codec,
            width = header.width,
            height = header.height,
            bitRate = buf.getInt(),
            frameRate = buf.getInt()
        )
    }

    fun writeCodecConfig(out: OutputStream, codec: Int, width: Int, height: Int, data: ByteArray) {
        writeHeader(out, TYPE_CODEC_CONFIG, codec, width, height, 0, data.size)
        out.write(data)
        out.flush()
    }

    fun writeFrame(out: OutputStream, codec: Int, width: Int, height: Int, pts: Long, data: ByteArray) {
        writeHeader(out, TYPE_VIDEO_FRAME, codec, width, height, pts, data.size)
        out.write(data)
        out.flush()
    }

    fun getCodecMime(codec: Int): String = when (codec) {
        CODEC_H265 -> "video/hevc"
        else -> "video/avc"
    }
}
