package app.musemovelog;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostic log appended to Downloads/ml_debug.txt (via MediaStore, since this
 * app has no all-files access), falling back to the app-specific external dir.
 * Never throws — diagnostics must not break the widget.
 */
public final class Dbg {
    private static final String TAG = "MoveLog";
    public static final String FILE_NAME = "ml_debug.txt";
    private static Uri cachedUri;

    private Dbg() {
    }

    public static synchronized void log(Context context, String message) {
        Log.d(TAG, message);
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " " + message + "\n";
        if (context == null) {
            return;
        }
        try {
            if (appendViaMediaStore(context, line)) {
                return;
            }
        } catch (Exception e) {
            Log.d(TAG, "MediaStore log failed: " + e);
        }
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(f, true)) {
                    fos.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean appendViaMediaStore(Context context, String line) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = cachedUri;
        if (uri == null) {
            uri = findExisting(resolver);
        }
        if (uri == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            uri = resolver.insert(MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) {
                return false;
            }
        }
        // "wa" = write + append (API 29+).
        try (OutputStream os = resolver.openOutputStream(uri, "wa")) {
            if (os == null) {
                cachedUri = null;
                return false;
            }
            os.write(line.getBytes(StandardCharsets.UTF_8));
        }
        cachedUri = uri;
        return true;
    }

    private static Uri findExisting(ContentResolver resolver) {
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND "
                + MediaStore.Downloads.RELATIVE_PATH + "=?";
        String[] args = {FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"};
        try (Cursor cursor = resolver.query(collection,
                new String[]{MediaStore.Downloads._ID}, selection, args, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                return Uri.withAppendedPath(collection, String.valueOf(id));
            }
        }
        return null;
    }
}
