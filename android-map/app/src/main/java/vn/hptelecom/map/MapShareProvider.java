package vn.hptelecom.map;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only access to a single explicitly shared file, with temporary URI grants. */
public final class MapShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (getContext() == null || !(getContext().getPackageName() + ".files").equals(uri.getAuthority())
                || parts.size() != 3 || !parts.get(0).equals("exports")
                || !parts.get(1).matches("[a-f0-9-]{36}")
                || parts.get(2).contains("/") || parts.get(2).contains("\\") || !parts.get(2).endsWith(".kmz"))
            throw new FileNotFoundException("Invalid shared file");
        try {
            File root = new File(getContext().getCacheDir(), "exports").getCanonicalFile();
            File file = new File(new File(root, parts.get(1)), parts.get(2)).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
                throw new FileNotFoundException("Shared file is unavailable");
            return file;
        } catch (IOException error) { throw new FileNotFoundException("Shared file is unavailable"); }
    }
    @Override public String getType(Uri uri) { return "application/vnd.google-earth.kmz"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only shared file");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values); return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
