package com.harkertech.migratefluttersecurestorage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.harkertech.migratefluttersecurestorage.ciphers.StorageCipher;
import com.harkertech.migratefluttersecurestorage.ciphers.StorageCipher18Implementation;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

@SuppressLint("ApplySharedPref")
public class MigrateFlutterSecureStoragePlugin implements MethodCallHandler, FlutterPlugin {

    private static final String TAG = "MigrateFlutterSecureStoragePl";

    private MethodChannel channel;
    private SharedPreferences preferences;
    private Charset charset;
    private StorageCipher storageCipher;
    // Necessary for deferred initialization of storageCipher.
    private Context applicationContext;
    private HandlerThread workerThread;
    private Handler workerThreadHandler;

    private static final String ELEMENT_PREFERENCES_KEY_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg";
    // Backwards compatibility
    private static final String SHARED_PREFERENCES_NAME = "com.HRWDevelopments.QuipCheck.xamarinessentials";

    public static void registerWith(Registrar registrar) {
      MigrateFlutterSecureStoragePlugin instance = new MigrateFlutterSecureStoragePlugin();
      instance.initInstance(registrar.messenger(), registrar.context());
    }

    public void initInstance(BinaryMessenger messenger, Context context) {
      try {
          applicationContext = context.getApplicationContext();
          preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
          charset = Charset.forName("UTF-8");

          workerThread = new HandlerThread("com.harkertech.migratefluttersecurestorage.worker");
          workerThread.start();
          workerThreadHandler = new Handler(workerThread.getLooper());

          channel = new MethodChannel(messenger, "plugins.harkertech.com/migrate_flutter_secure_storage");
          channel.setMethodCallHandler(this);
      } catch (Exception e) {
          Log.e(TAG, "Registration failed", e);
      }
    }

    private void ensureInitStorageCipher() {
        if (storageCipher == null) {
            try {
                storageCipher = new StorageCipher18Implementation(applicationContext);
            } catch (Exception e) {
                Log.e(TAG, "StorageCipher initialization failed", e);
            }
        }
    }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
      initInstance(binding.getBinaryMessenger(), binding.getApplicationContext());
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
      if (channel != null) {
        workerThread.quitSafely();
        workerThread = null;

        channel.setMethodCallHandler(null);
        channel = null;
      }
    }

    @Override
    public void onMethodCall(MethodCall call, Result rawResult) {
        MethodResultWrapper result = new MethodResultWrapper(rawResult);
        // Run all method calls inside the worker thread instead of the platform thread.
        workerThreadHandler.post(new MethodRunner(call, result));
    }

    private String getKeyFromCall(MethodCall call) {
        Map arguments = (Map) call.arguments;
        String rawKey = (String) arguments.get("key");
        // Backwards compatibility
        // String key = addPrefixToKey(rawKey);
        String key = rawKey;
        return key;
    }

    private Map<String, String> readAll() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> raw = (Map<String, String>) preferences.getAll();

        Map<String, String> all = new HashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey();
            try {
                String rawValue = entry.getValue();
                String value = decodeRawValue(rawValue);

                all.put(key, value);
            } catch  (ClassCastException e) {
                // Backwards compatibility
                // Essentials inserted a boolean which is not supported by the plugin
                Log.w(TAG, String.format("Read all cast to string failed for key '%s'", key));
            }
        }
        return all;
    }

    private void deleteAll() {
        SharedPreferences.Editor editor = preferences.edit();

        editor.clear();
        editor.commit();
    }

    private void write(String key, String value) throws Exception {
        byte[] result = storageCipher.encrypt(value.getBytes(charset));
        SharedPreferences.Editor editor = preferences.edit();

        editor.putString(key, Base64.encodeToString(result, 0));
        editor.commit();
    }

    private String read(String key) throws Exception {
        String encoded = preferences.getString(key, null);

        return decodeRawValue(encoded);
    }

    private void delete(String key) {
        SharedPreferences.Editor editor = preferences.edit();

        editor.remove(key);
        editor.commit();
    }

    private String addPrefixToKey(String key) {
        return ELEMENT_PREFERENCES_KEY_PREFIX + "_" + key;
    }

    private String decodeRawValue(String value) throws Exception {
        if (value == null) {
            return null;
        }
        byte[] data = Base64.decode(value, 0);
        byte[] result = storageCipher.decrypt(data);

        return new String(result, charset);
    }

    /**
     * Wraps the functionality of onMethodCall() in a Runnable for execution in the worker thread.
     */
    class MethodRunner implements Runnable {
        private final MethodCall call;
        private final Result result;

        MethodRunner(MethodCall call, Result result) {
            this.call = call;
            this.result = result;
        }

        @Override
        public void run() {
            try {
                switch (call.method) {
                    case "write": {
                        ensureInitStorageCipher();

                        String key = getKeyFromCall(call);
                        Map arguments = (Map) call.arguments;

                        String value = (String) arguments.get("value");
                        write(key, value);
                        result.success(null);
                        break;
                    }
                    case "read": {
                        String key = getKeyFromCall(call);

                        if (preferences.contains(key)) {
                            ensureInitStorageCipher();

                            String value = read(key);
                            result.success(value);
                        } else {
                            result.success(null);
                        }
                        break;
                    }
                    case "readAll": {
                        ensureInitStorageCipher();

                        Map<String, String> value = readAll();
                        result.success(value);
                        break;
                    }
                    case "delete": {
                        String key = getKeyFromCall(call);

                        delete(key);
                        result.success(null);
                        break;
                    }
                    case "deleteAll": {
                        deleteAll();
                        result.success(null);
                        break;
                    }
                    default:
                        result.notImplemented();
                        break;
                }

            } catch (Exception e) {
                StringWriter stringWriter = new StringWriter();
                e.printStackTrace(new PrintWriter(stringWriter));
                result.error("Exception encountered", call.method, stringWriter.toString());
            }
        }
    }

    /**
     * MethodChannel.Result wrapper that responds on the platform thread.
     */
    static class MethodResultWrapper implements Result {

        private final Result methodResult;
        private final Handler handler = new Handler(Looper.getMainLooper());

        MethodResultWrapper(Result methodResult) {
            this.methodResult = methodResult;
        }

        @Override
        public void success(final Object result) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.success(result);
                }
            });
        }

        @Override
        public void error(final String errorCode, final String errorMessage, final Object errorDetails) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.error(errorCode, errorMessage, errorDetails);
                }
            });
        }

        @Override
        public void notImplemented() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    methodResult.notImplemented();
                }
            });
        }
    }
}
