package dev.quest.runtime;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.Binder;
import java.io.*;

/** Fixed bootstrap files, protected by the platform's DUMP permission. */
public class RuntimeFiles extends ContentProvider {
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) {
        // ContentProvider.call requires an explicit permission check.
        getContext().enforceCallingOrSelfPermission("android.permission.DUMP", "ADB bootstrap only");
        long token = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(getContext(), RuntimeService.class);
            if ("start".equals(method)) getContext().startForegroundService(intent);
            else if ("stop".equals(method)) getContext().stopService(intent);
            else throw new IllegalArgumentException("Unsupported method");
            Bundle result = new Bundle(); result.putString("result", method + " requested");
            return result;
        } finally { Binder.restoreCallingIdentity(token); }
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        boolean write = mode.contains("w");
        String path = uri.getPath();
        File root = getContext().getFilesDir();
        File file;
        if ("/authorized_keys".equals(path)) {
            File dir = new File(root, "home/.ssh");
            dir.mkdirs();
            dir.setReadable(false, false); dir.setReadable(true, true);
            dir.setWritable(false, false); dir.setWritable(true, true);
            dir.setExecutable(false, false); dir.setExecutable(true, true);
            file = new File(dir, "authorized_keys");
        } else if ("/tailnet_ip".equals(path)) {
            file = new File(root, "tailnet_ip");
        } else if (!write && "/status".equals(path)) {
            file = new File(root, "status.txt");
        } else if (!write && "/log".equals(path)) {
            file = new File(root, "runtime.log");
        } else if (!write && "/autostart".equals(path)) {
            file = new File(root, "autostart.txt");
        } else if (!write && "/host_public_key".equals(path)) {
            file = new File(root, "host_public_key.txt");
        } else {
            throw new FileNotFoundException("Unsupported runtime path or mode");
        }
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, write
            ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE |
                ParcelFileDescriptor.MODE_TRUNCATE
            : ParcelFileDescriptor.MODE_READ_ONLY);
        if (write) {
            file.setReadable(false, false); file.setReadable(true, true);
            file.setWritable(false, false); file.setWritable(true, true);
        }
        return fd;
    }
    public String getType(Uri u) { return "application/octet-stream"; }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
