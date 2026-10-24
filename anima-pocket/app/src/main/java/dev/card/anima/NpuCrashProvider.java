package dev.card.anima;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Grants read-only access to the captured native tombstone through Android Sharesheet. */
public final class NpuCrashProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.card.anima.npucrash";
    private File tombstone(Uri uri) throws FileNotFoundException {
        if (uri == null || !"/npu-tombstone.pb".equals(uri.getPath())) {
            throw new FileNotFoundException("Unknown diagnostic path");
        }
        File f = new File(new File(getContext().getFilesDir(), "diagnostics"), "npu-tombstone.pb");
        if (!f.isFile()) throw new FileNotFoundException("No native tombstone captured");
        return f;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(tombstone(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = tombstone(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
            MatrixCursor c = new MatrixCursor(columns);
            Object[] vals = new Object[columns.length];
            for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) vals[i] = "anima-npu-tombstone.pb";
                else if (OpenableColumns.SIZE.equals(columns[i])) vals[i] = f.length();
            }
            c.addRow(vals);
            return c;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
}
