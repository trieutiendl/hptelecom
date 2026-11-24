package vn.hptelecom.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Original files and their extracted KML stay inside this app's private storage. */
final class ImportedMapStore {
    static final class MapFile {
        final String id, name;
        final byte[] kml;
        final boolean duplicate;
        MapFile(String id, String name, byte[] kml, boolean duplicate) {
            this.id = id; this.name = name; this.kml = kml; this.duplicate = duplicate;
        }
    }
    private final File root;
    ImportedMapStore(File root) { this.root = root; }

    synchronized MapFile add(String suppliedName, byte[] original) throws IOException {
        if (original.length == 0 || original.length > BridgePolicy.MAX_FILE) throw new IOException("Tệp phải nhỏ hơn 16 MB.");
        String id = BridgePolicy.sha256(original);
        List<MapFile> existing = list();
        for (MapFile file : existing) if (file.id.equals(id)) return new MapFile(id, file.name, file.kml, true);
        if (existing.size() >= BridgePolicy.MAX_MAPS) throw new IOException("Đã lưu 10 bản đồ. Hãy xóa bản đồ không cần trong mục Bản đồ đã nhập.");
        byte[] kml = extractKml(original);
        long total = kml.length;
        for (MapFile file : existing) total += file.kml.length;
        if (total > BridgePolicy.MAX_ALL_KML) throw new IOException("Tổng dữ liệu bản đồ vượt 16 MB. Hãy xóa bản đồ không cần.");
        String name = BridgePolicy.fileName(suppliedName, BridgePolicy.zipSignature(original) ? ".kmz" : ".kml");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Không tạo được thư mục bản đồ.");
        File folder = new File(root, id);
        if (folder.exists()) remove(id);
        if (!folder.mkdir()) throw new IOException("Không tạo được bản đồ lưu trữ.");
        try {
            write(new File(folder, "original"), original);
            write(new File(folder, "doc.kml"), kml);
            // Name is the commit marker; incomplete imports never appear in list().
            write(new File(folder, "name.txt"), name.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) { remove(id); throw failure; }
        return new MapFile(id, name, kml, false);
    }

    synchronized List<MapFile> list() throws IOException {
        List<MapFile> result = new ArrayList<>();
        File[] folders = root.listFiles();
        if (folders == null) return result;
        Arrays.sort(folders, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File folder : folders) {
            if (!folder.isDirectory() || !folder.getName().matches("[a-f0-9]{64}")) continue;
            File nameFile = new File(folder, "name.txt"), kmlFile = new File(folder, "doc.kml");
            if (!nameFile.isFile() || !kmlFile.isFile()) continue;
            if (result.size() >= BridgePolicy.MAX_MAPS) throw new IOException("Danh sách bản đồ vượt giới hạn an toàn.");
            byte[] kml;
            String name;
            try (FileInputStream input = new FileInputStream(kmlFile)) { kml = BridgePolicy.readBounded(input, BridgePolicy.MAX_KML); }
            BridgePolicy.utf8Kml(kml);
            total += kml.length;
            if (total > BridgePolicy.MAX_ALL_KML) throw new IOException("Danh sách bản đồ vượt giới hạn 16 MB.");
            try (FileInputStream input = new FileInputStream(nameFile)) { name = new String(BridgePolicy.readBounded(input, 1024), StandardCharsets.UTF_8); }
            result.add(new MapFile(folder.getName(), name, kml, false));
        }
        return result;
    }

    synchronized void remove(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IOException("Mã bản đồ không hợp lệ.");
        File folder = new File(root, id);
        if (!folder.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IOException("Đường dẫn bản đồ không hợp lệ.");
        for (String name : new String[]{"name.txt", "doc.kml", "original"}) {
            File file = new File(folder, name);
            if (file.exists() && !file.delete()) throw new IOException("Chưa xóa được bản đồ.");
        }
        if (folder.exists() && !folder.delete()) throw new IOException("Chưa xóa được thư mục bản đồ.");
    }

    static byte[] extractKml(byte[] original) throws IOException {
        if (!BridgePolicy.zipSignature(original)) {
            BridgePolicy.utf8Kml(original);
            return original;
        }
        byte[] chosen = null;
        boolean rootDocChosen = false;
        int count = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(original))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > BridgePolicy.MAX_ZIP_ENTRIES) throw new IOException("KMZ có quá nhiều mục.");
                if (!BridgePolicy.safeZipName(entry.getName())) throw new IOException("KMZ chứa đường dẫn không an toàn.");
                String name = entry.getName().toLowerCase(Locale.ROOT);
                boolean candidate = !entry.isDirectory() && name.endsWith(".kml");
                boolean keep = candidate && (chosen == null || name.equals("doc.kml") && !rootDocChosen);
                ByteArrayOutputStream output = keep ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n; entryBytes += n;
                    if (total > BridgePolicy.MAX_ZIP_BYTES || candidate && entryBytes > BridgePolicy.MAX_KML)
                        throw new IOException("KMZ giải nén vượt giới hạn an toàn.");
                    if (keep) output.write(buffer, 0, n);
                }
                if (keep) { chosen = output.toByteArray(); rootDocChosen = name.equals("doc.kml"); }
                zip.closeEntry();
            }
        } catch (IllegalArgumentException error) { throw new IOException("Không đọc được cấu trúc KMZ."); }
        if (chosen == null) throw new IOException("KMZ không chứa tệp KML.");
        BridgePolicy.utf8Kml(chosen);
        return chosen;
    }

    private static void write(File destination, byte[] bytes) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(destination)) {
            stream.write(bytes); stream.flush(); stream.getFD().sync();
        }
    }
}
