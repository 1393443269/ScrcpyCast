package com.scrcpycast.server;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Base64;

public class LeboCastManager {

    private static final String TAG = "LeboCast";

    public interface CastStateListener {
        void onStateChanged(String state);
        void onError(String error);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean handshakeDone = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String deviceIp;
    private int devicePort;
    private Socket tcpSocket;
    private OutputStream tcpOut;
    private InputStream tcpIn;
    private Thread sendThread;
    private String sessionId = "DEADBEEF";
    private CastStateListener listener;

    private int seqNumber = 0;
    private int ssrc = 0x12345678;
    private byte[] pendingCsd = null;
    private byte[] initialCsd = null;

    private static final int RTP_CHANNEL = 0x00;

    public void setListener(CastStateListener listener) {
        this.listener = listener;
    }

    public void start(String ip, int port, int width, int height) {
        start(ip, port, width, height, null);
    }

    public void start(String ip, int port, int width, int height, byte[] csd) {
        if (!running.compareAndSet(false, true)) return;

        deviceIp = ip;
        devicePort = port;
        initialCsd = csd;

        sendThread = new Thread(() -> {
            firstIdrSeen = false;
            framesSent = 0;
            try {
                updateState("连接中...");
                if (!connectTcp()) return;

                updateState("发送ANNOUNCE...");
                if (!sendAnnounce(width, height)) {
                    updateError("ANNOUNCE失败");
                    stop();
                    return;
                }

                updateState("建立传输(SETUP)...");
                if (!setupTransport()) {
                    updateError("SETUP失败");
                    stop();
                    return;
                }

                updateState("开始推流(RECORD)...");
                if (!startRecord()) {
                    updateError("RECORD失败");
                    stop();
                    return;
                }

                updateState("推流已就绪");
                // 1. Send SPS/PPS before handshakeDone so decoder has codec config first
                flushPendingCsd();
                // 2. Request IDR before allowing frames through, so the first frame is an IDR
                com.scrcpycast.server.ServerService.Companion.setRequestKeyFrame(true);
                Log.d(TAG, "Requested IDR frame from encoder");
                // 3. Now allow sendEncodedFrame to pass data (SPS/PPS + IDR ready)
                handshakeDone.set(true);
            } catch (Exception e) {
                Log.e(TAG, "Setup error: " + e.getMessage());
                updateError("设置失败: " + e.getMessage());
                stop();
            }
        });
        sendThread.start();
    }

    private boolean connectTcp() {
        try {
            tcpSocket = new Socket(deviceIp, devicePort);
            tcpSocket.setSoTimeout(8000);
            tcpOut = tcpSocket.getOutputStream();
            tcpIn = tcpSocket.getInputStream();
            Log.d(TAG, "TCP connected to " + deviceIp + ":" + devicePort);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TCP connect failed: " + e.getMessage());
            updateError("连接失败: " + e.getMessage());
            return false;
        }
    }

    private String buildSpropParameterSets(byte[] csd) {
        if (csd == null) return "";
        StringBuilder sb = new StringBuilder();
        int end = csd.length;
        int pos = findStartCode(csd, 0, end);
        while (pos >= 0) {
            int nextStart = findNextStartCode(csd, pos, end);
            int nalEnd = (nextStart >= 0) ? nextStart : end;
            int nalLength = nalEnd - pos;
            if (nalLength > 0) {
                if (sb.length() > 0) sb.append(",");
                String b64 = Base64.encodeToString(csd, pos, nalLength, Base64.NO_WRAP);
                sb.append(b64);
            }
            if (nextStart < 0) break;
            pos = findStartCode(csd, nextStart, end);
            if (pos < 0) break;
        }
        if (sb.length() > 0) {
            return ";sprop-parameter-sets=" + sb.toString();
        }
        return "";
    }

    private String buildProfileLevelId(byte[] csd) {
        if (csd == null) return "428028"; // default Baseline 4.0
        // Find SPS NAL unit in Annex B format
        int end = csd.length;
        int pos = findStartCode(csd, 0, end);
        if (pos < 0) return "428028";
        int nalType = csd[pos] & 0x1f;
        if (nalType != 7) return "428028"; // not SPS
        // SPS: NAL_header(1) + profile_idc(1) + constraints(1) + level_idc(1)
        // Skip NAL header byte to get SPS content
        if (pos + 4 > end) return "428028";
        int profileIdc = csd[pos + 1] & 0xFF;
        int constraints = csd[pos + 2] & 0xFF;
        int levelIdc = csd[pos + 3] & 0xFF;
        return String.format("%02X%02X%02X", profileIdc, constraints, levelIdc);
    }

    private boolean sendAnnounce(int width, int height) {
        try {
            String sessionId = "11634020164747084845";
            String sprop = buildSpropParameterSets(initialCsd);
            String profileLevelId = buildProfileLevelId(initialCsd);
            Log.d(TAG, "sprop-parameter-sets:" + sprop + " profile-level-id=" + profileLevelId);
            String sdp = "v=0\r\n"
                    + "o=hpplay 0 0 IN IP4 0.0.0.0\r\n"
                    + "s=hpplay\r\n"
                    + "c=IN IP4 0.0.0.0\r\n"
                    + "t=0 0\r\n"
                    + "m=video 0 RTP/AVP 96\r\n"
                    + "a=rtpmap:96 H264/90000\r\n"
                    + "a=fmtp:96 packetization-mode=1;profile-level-id=" + profileLevelId + sprop + "\r\n";

            String request = "ANNOUNCE rtsp://" + deviceIp + ":" + devicePort + "/" + sessionId + " RTSP/1.0\r\n"
                    + "CSeq: 1\r\n"
                    + "User-Agent: scrcpycast\r\n"
                    + "Content-Length: " + sdp.length() + "\r\n"
                    + "Content-Type: application/sdp\r\n"
                    + "\r\n"
                    + sdp;

            tcpOut.write(request.getBytes("UTF-8"));
            tcpOut.flush();
            Log.d(TAG, "Sent ANNOUNCE");

            String response = readResponse();
            Log.d(TAG, "ANNOUNCE response: " + response);
            return response != null && response.contains("200");
        } catch (Exception e) {
            Log.e(TAG, "ANNOUNCE error: " + e.getMessage());
            return false;
        }
    }

    private boolean setupTransport() {
        try {
            String request = "SETUP rtsp://" + deviceIp + ":" + devicePort + "/41/video RTSP/1.0\r\n"
                    + "CSeq: 2\r\n"
                    + "User-Agent: scrcpycast\r\n"
                    + "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;mode=record\r\n"
                    + "\r\n";

            tcpOut.write(request.getBytes("UTF-8"));
            tcpOut.flush();
            Log.d(TAG, "Sent SETUP");

            String response = readResponse();
            Log.d(TAG, "SETUP response: " + response);
            if (response == null) return false;

            if (response.contains("Session:")) {
                String s = extractBetween(response, "Session:", "\r");
                if (s == null) s = extractBetween(response, "Session:", ";");
                if (s != null) sessionId = s.trim();
                Log.d(TAG, "Session: " + sessionId);
            }

            return response.contains("200");
        } catch (Exception e) {
            Log.e(TAG, "SETUP error: " + e.getMessage());
            return false;
        }
    }

    private boolean startRecord() {
        try {
            String request = "RECORD rtsp://" + deviceIp + ":" + devicePort + "/41 RTSP/1.0\r\n"
                    + "CSeq: 3\r\n"
                    + "User-Agent: scrcpycast\r\n"
                    + "Session: " + sessionId + "\r\n"
                    + "Range: npt=0-\r\n"
                    + "\r\n";

            tcpOut.write(request.getBytes("UTF-8"));
            tcpOut.flush();
            Log.d(TAG, "Sent RECORD");

            String response = readResponse();
            Log.d(TAG, "RECORD response: " + response);
            return response != null && response.contains("200");
        } catch (Exception e) {
            Log.e(TAG, "RECORD error: " + e.getMessage());
            return false;
        }
    }

    private int framesSent = 0;
    /** Tracks whether we've sent the first IDR; non-IDR frames are dropped until then. */
    private boolean firstIdrSeen = false;

    /**
     * Send H.264 encoded frame data (Annex B format: 00 00 00 01 start codes).
     * Parses the buffer into individual NAL units and sends each as RTP.
     * Non-IDR frames are dropped until the first IDR is seen, so the decoder
     * always starts from a clean key frame.
     */
    public void sendEncodedFrame(byte[] data, int offset, int length, long pts) {
        if (!running.get() || tcpOut == null || !handshakeDone.get()) {
            return;
        }

        try {
            int rtpTimestamp = (int)((pts / 1000) * 90);
            int count = 0;
            int end = offset + length;
            boolean hasIdr = false;

            // Parse Annex B format: find start codes (00 00 01 or 00 00 00 01)
            int pos = findStartCode(data, offset, end);
            while (pos >= 0) {
                // pos points to the first byte after the start code (NAL header)
                int nextStart = findNextStartCode(data, pos, end);
                int nalEnd = (nextStart >= 0) ? nextStart : end;
                int nalLength = nalEnd - pos;

                if (nalLength > 0) {
                    int nalType = data[pos] & 0x1f;
                    if (nalType == 5) hasIdr = true;  // IDR slice
                    if (!firstIdrSeen) {
                        // Skip all NAL units until we see an IDR
                        if (nalType == 5) {
                            firstIdrSeen = true;  // First IDR — send it
                        } else {
                            if (nextStart < 0) break;
                            pos = findStartCode(data, nextStart, end);
                            if (pos < 0) break;
                            else continue;
                        }
                    }
                    sendNalUnit(data, pos, nalLength, rtpTimestamp, nalType);
                    count++;
                    if (framesSent <= 3) {
                        Log.d(TAG, "  NAL type=" + nalType + " size=" + nalLength);
                    }
                }

                if (nextStart < 0) break;
                // Advance past the full start code to avoid false detection
                // of 00 00 01 within a 4-byte 00 00 00 01 start code
                pos = findStartCode(data, nextStart, end);
                if (pos < 0) break;
            }

            if (count > 0) {
                framesSent++;
                if (hasIdr) {
                    if (framesSent == 1) {
                        Log.d(TAG, "First IDR sent, starting RTP stream");
                    } else {
                        Log.d(TAG, "IDR frame #" + framesSent + " sent");
                    }
                }
                if (framesSent <= 5 || framesSent % 30 == 0) {
                    Log.d(TAG, "RTP sent frame #" + framesSent + " size=" + length + " nals=" + count + " pts=" + pts);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Send error: " + e.getMessage() + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Send codec configuration (SPS/PPS) as RTP single NAL unit packets.
     * Expects csd-0 in Annex B format (with 00 00 00 01 start codes).
     */
    public void sendCodecConfig(byte[] csd) {
        // Always store pendingCsd even if not running yet, so it can be flushed
        // after the RTSP handshake completes (encoder generates csd before start() sets running=true)
        pendingCsd = csd;
        if (!running.get()) {
            Log.d(TAG, "sendCodecConfig: csd.size=" + csd.length + " (pending, not running yet)");
            return;
        }
        if (tcpOut != null && handshakeDone.get()) {
            // Both TCP and handshake ready — send immediately
            Log.d(TAG, "sendCodecConfig: csd.size=" + csd.length + " (sending immediately)");
            flushPendingCsd();
        } else {
            Log.d(TAG, "sendCodecConfig: csd.size=" + csd.length + " (pending, tcpOut=" + (tcpOut!=null) + " handshake=" + handshakeDone.get() + ")");
        }
    }

    private void flushPendingCsd() {
        byte[] csd = pendingCsd;
        if (csd == null) return;
        pendingCsd = null;
        try {
            // Log encoder SPS/PPS info from csd-0
            StringBuilder hexDump = new StringBuilder();
            for (int i = 0; i < Math.min(csd.length, 24); i++) {
                hexDump.append(String.format("%02X ", csd[i] & 0xFF));
            }
            Log.d(TAG, "csd-0 hex: " + hexDump.toString() + " size=" + csd.length);

            // Parse Annex B format to extract SPS/PPS NAL units
            int rtpTimestamp = 0;
            int end = csd.length;
            int pos = findStartCode(csd, 0, end);
            while (pos >= 0) {
                int nextStart = findNextStartCode(csd, pos, end);
                int nalEnd = (nextStart >= 0) ? nextStart : end;
                int nalLength = nalEnd - pos;

                if (nalLength > 0) {
                    int nalType = csd[pos] & 0x1f;
                    sendNalUnit(csd, pos, nalLength, rtpTimestamp, nalType);
                    Log.d(TAG, "  csd-0 NAL type=" + nalType + " size=" + nalLength);
                }

                if (nextStart < 0) break;
                // Advance past the full start code to avoid false detection
                // of 00 00 01 within a 4-byte 00 00 00 01 start code
                pos = findStartCode(csd, nextStart, end);
                if (pos < 0) break;
            }

            Log.d(TAG, "Codec config sent: SPS/PPS RTP packets");
        } catch (Exception e) {
            Log.e(TAG, "Codec config send error: " + e.getMessage());
        }
    }

    /** Find the first Annex B start code at or after position pos.
     *  Returns the position of the first byte AFTER the start code, or -1. */
    private int findStartCode(byte[] data, int start, int end) {
        for (int i = start; i + 2 < end; i++) {
            if (data[i] == 0 && data[i+1] == 0) {
                if (data[i+2] == 1) return i + 3;          // 3-byte: 00 00 01
                if (i + 3 < end && data[i+2] == 0 && data[i+3] == 1) return i + 4; // 4-byte: 00 00 00 01
            }
        }
        return -1;
    }

    /** Find the next Annex B start code after the given position.
     *  Start code must be at least 1 byte after pos (NAL content can have 00 00 00 sequences). */
    private int findNextStartCode(byte[] data, int pos, int end) {
        for (int i = pos + 1; i + 2 < end; i++) {
            if (data[i] == 0 && data[i+1] == 0) {
                if (data[i+2] == 1) return i;              // Start code starts at i
                if (i + 3 < end && data[i+2] == 0 && data[i+3] == 1) return i;
            }
        }
        return -1;
    }

    private void sendNalUnit(byte[] data, int offset, int length, int rtpTimestamp, int nalType) throws Exception {
        if (length <= 1400) {
            // Single NAL unit packet
            ByteBuffer rtp = buildRtpHeader(true, rtpTimestamp);
            rtp.put(data, offset, length);
            sendInterleaved(rtp.array(), rtp.position());
        } else {
            // FU-A fragmentation: skip the first byte (NAL header) in the payload
            int nalHeader = data[offset] & 0xFF;  // F(1) + NRI(2) + Type(5)
            int startOffset = offset + 1;
            int remaining = length - 1;
            boolean first = true;

            while (remaining > 0) {
                int chunkSize = Math.min(remaining, 1400);
                boolean isLast = (remaining <= chunkSize);
                ByteBuffer rtp = buildRtpHeader(isLast, rtpTimestamp);

                // FU indicator: preserve F and NRI from original NAL header, set type to 28 (FU-A)
                int fuIndicator = (nalHeader & 0xE0) | 28;
                rtp.put((byte) fuIndicator);

                // FU header: S(1) + E(1) + R(1) + Type(5)
                int fuHeader = nalType & 0x1f;
                if (first) {
                    fuHeader |= 0x80; // Start bit
                    first = false;
                }
                if (isLast) {
                    fuHeader |= 0x40; // End bit
                }
                rtp.put((byte) fuHeader);

                rtp.put(data, startOffset, chunkSize);
                sendInterleaved(rtp.array(), rtp.position());

                startOffset += chunkSize;
                remaining -= chunkSize;
            }
        }
    }

    private ByteBuffer buildRtpHeader(boolean marker, int timestamp) {
        ByteBuffer buf = ByteBuffer.allocate(12 + 2 + 1400); // RTP header + FU-A indicator/header + max payload
        buf.put((byte) 0x80); // V=2, P=0, X=0, CC=0
        buf.put((byte) (marker ? 0xe0 : 0x60)); // M, PT=96
        buf.putShort((short) (seqNumber++ & 0xFFFF));
        buf.putInt(timestamp);
        buf.putInt(ssrc);
        return buf;
    }

    private void sendInterleaved(byte[] data, int length) throws Exception {
        byte[] header = new byte[4];
        header[0] = 0x24; // '$'
        header[1] = (byte) RTP_CHANNEL;
        header[2] = (byte) ((length >> 8) & 0xFF);
        header[3] = (byte) (length & 0xFF);

        synchronized (tcpOut) {
            tcpOut.write(header);
            tcpOut.write(data, 0, length);
            tcpOut.flush();
        }
    }

    private String readResponse() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = System.currentTimeMillis() + 5000;

            while (System.currentTimeMillis() < deadline) {
                int avail = tcpIn.available();
                if (avail > 0) {
                    int n = tcpIn.read(buffer, 0, Math.min(avail, buffer.length));
                    if (n < 0) break;
                    baos.write(buffer, 0, n);

                    // Check if we have a complete RTSP response (ends with \r\n\r\n)
                    byte[] data = baos.toByteArray();
                    if (data.length >= 4) {
                        String s = new String(data, "UTF-8");
                        if (s.contains("\r\n\r\n")) {
                            int contentLen = 0;
                            for (String line : s.split("\r\n")) {
                                if (line.toLowerCase().startsWith("content-length:")) {
                                    contentLen = Integer.parseInt(line.split(":")[1].trim());
                                    break;
                                }
                            }
                            int headerEnd = s.indexOf("\r\n\r\n") + 4;
                            if (headerEnd + contentLen <= data.length) {
                                return s;
                            }
                        }
                    }
                } else {
                    Thread.sleep(50);
                }
            }

            byte[] data = baos.toByteArray();
            if (data.length > 0) {
                return new String(data, "UTF-8");
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Read error: " + e.getMessage());
            return null;
        }
    }

    private String extractBetween(String str, String start, String end) {
        int i = str.indexOf(start);
        if (i < 0) return null;
        i += start.length();
        int j = str.indexOf(end, i);
        if (j < 0) return str.substring(i).trim();
        return str.substring(i, j).trim();
    }

    public void stop() {
        running.set(false);
        handshakeDone.set(false);
        pendingCsd = null;
        try {
            if (tcpSocket != null) {
                tcpSocket.close();
            }
        } catch (Exception ignored) {}
        tcpSocket = null;
        tcpOut = null;
        tcpIn = null;
    }

    private void updateState(String state) {
        mainHandler.post(() -> {
            if (listener != null) listener.onStateChanged(state);
        });
    }

    private void updateError(String error) {
        mainHandler.post(() -> {
            if (listener != null) listener.onError(error);
        });
    }
}
