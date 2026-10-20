package vn.hptelecom.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

/** Pure-Java limits and validation, shared by the Android bridge and JVM tests. */
final class BridgePolicy {
    static final String HOST = "hptelecom.id.vn";
    static final String START_URL = "https://hptelecom.id.vn/tonghop?mapapp=1";
    static final String LOGIN_URL = "https://hptelecom.id.vn/dangnhap";
    static final int MAX_FILE = 16 * 1024 * 1024;
    static final int MAX_KML = 8 * 1024 * 1024;
    static final int MAX_ALL_KML = 16 * 1024 * 1024;
    static final int MAX_ZIP_BYTES = 32 * 1024 * 1024;
    static final int MAX_ZIP_ENTRIES = 256;
    static final int MAX_MAPS = 10;

    static boolean trustedUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && HOST.equalsIgnoreCase(uri.getHost())
                && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ignored) { return false; }
    }

    static boolean isLoginUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!trustedUrl(value)) return false;
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            return path.equals("/dangnhap") || path.startsWith("/dangnhap/")
                || path.equals("/login") || path.startsWith("/login/");
        } catch (Exception ignored) { return false; }
    }

    static String requestId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}") ? value : "native";
    }

    static String fileName(String input, String extension) {
        String value = input == null ? "Ban-do-tuyen-cap" : input.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (value.startsWith(".")) value = value.substring(1);
        if (value.isEmpty()) value = "Ban-do-tuyen-cap";
        if (value.length() > 100) value = value.substring(0, 100);
        if (!value.toLowerCase(Locale.ROOT).endsWith(extension)) value += extension;
        return value;
    }

    static byte[] readBounded(InputStream stream, int limit) throws IOException {
        if (stream == null) throw new IOException("Không đọc được tệp đã chọn.");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 32768));
        byte[] buffer = new byte[16384];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() > limit - count) throw new IOException("Tệp vượt giới hạn dung lượng cho phép.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static String utf8Kml(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_KML) throw new IOException("Nội dung KML phải nhỏ hơn 8 MB.");
        final String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IOException("Tệp KML cần dùng mã hóa UTF-8."); }
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY") || value.indexOf('\0') >= 0)
            throw new IOException("KML chứa khai báo không được hỗ trợ.");
        if (!upper.contains("<KML") && !upper.matches("(?s).*<[A-Z_][A-Z0-9_.-]*:KML(?:\\s|>).*"))
            throw new IOException("Tệp không chứa nội dung KML hợp lệ.");
        return value;
    }

    static boolean safeZipName(String name) {
        if (name == null || name.isEmpty() || name.length() > 512 || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1)) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    static boolean zipSignature(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
            && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6));
    }

    static byte[] exportedKmz(String value) throws IOException {
        if (value == null || value.length() > ((MAX_FILE + 2L) / 3) * 4)
            throw new IOException("KMZ vượt giới hạn 16 MB.");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > MAX_FILE || !zipSignature(bytes)) throw new IOException("Nội dung KMZ không hợp lệ.");
            return bytes;
        } catch (IllegalArgumentException error) { throw new IOException("Không đọc được nội dung KMZ."); }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : hash) value.append(String.format(Locale.ROOT, "%02x", item & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
