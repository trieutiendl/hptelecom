package vn.hptelecom.map;

import java.io.IOException;

/** Builds only a fixed Google Maps destination URL, with no server data or tokens. */
final class MapDirections {
    static String url(double lat, double lon) throws IOException {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
            throw new IOException("Điểm này chưa có tọa độ hợp lệ để chỉ đường.");
        return "https://www.google.com/maps/dir/?api=1&destination="
                + Double.toString(lat) + "%2C" + Double.toString(lon);
    }
}
