package app.xingluo.video;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

/** Lightweight ContentProvider to share/open saved files safely on Android 9 (API 28) without AndroidX. */
public final class XingluoFileProvider extends ContentProvider {
    public static final String AUTHORITY = "app.xingluo.video.files";

    public static Uri uriForFile(File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("media")
                .appendQueryParameter("path", file.getAbsolutePath())
                .build();
    }

    private static File resolveSafeFile(Context context, Uri uri) throws FileNotFoundException {
        String raw = uri.getQueryParameter("path");
        if (raw == null || raw.isBlank()) throw new FileNotFoundException("Missing path");
        try {
            File target = new File(raw).getCanonicalFile();
            List<File> roots = new ArrayList<>();
            if (context.getFilesDir() != null) roots.add(context.getFilesDir().getCanonicalFile());
            if (context.getExternalFilesDir(null) != null) roots.add(context.getExternalFilesDir(null).getCanonicalFile());
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) roots.add(ext.getCanonicalFile());
            String targetPath = target.getPath();
            for (File root : roots) {
                String rp = root.getPath();
                if (targetPath.equals(rp) || targetPath.startsWith(rp + File.separator)) {
                    if (target.exists() && target.isFile()) return target;
                }
            }
        } catch (IOException ignored) {}
        throw new FileNotFoundException("File not accessible: " + raw);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String raw = uri.getQueryParameter("path");
        if (raw == null) return "application/octet-stream";
        String ext = MimeTypeMap.getFileExtensionFromUrl(raw.toLowerCase(Locale.ROOT));
        String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null) return mime;
        if (raw.toLowerCase(Locale.ROOT).endsWith(".mp4")) return "video/mp4";
        if (raw.toLowerCase(Locale.ROOT).endsWith(".jpg") || raw.toLowerCase(Locale.ROOT).endsWith(".jpeg")) return "image/jpeg";
        if (raw.toLowerCase(Locale.ROOT).endsWith(".png")) return "image/png";
        if (raw.toLowerCase(Locale.ROOT).endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) throw new SecurityException("Read-only provider");
        File f = resolveSafeFile(getContext(), uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        try {
            File f = resolveSafeFile(getContext(), uri);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            }
            cursor.addRow(row);
        } catch (FileNotFoundException ignored) {}
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        try {
            File f = resolveSafeFile(getContext(), uri);
            return f.delete() ? 1 : 0;
        } catch (FileNotFoundException e) { return 0; }
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
