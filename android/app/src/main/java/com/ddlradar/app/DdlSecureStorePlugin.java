package com.ddlradar.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Auth tokens only: encrypted by AndroidKeyStore, excluded from device backups. */
@CapacitorPlugin(name = "DdlSecureStore")
public class DdlSecureStorePlugin extends Plugin {
    private static final String ALIAS = "ddlr_auth_v1";
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySize(256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private AtomicFile file(String name) throws Exception {
        if (name == null || !name.matches("ddlr-auth[-a-z0-9_]*")) throw new IllegalArgumentException();
        return new AtomicFile(new File(getContext().getNoBackupFilesDir(), name + ".enc"));
    }
    @PluginMethod public synchronized void get(PluginCall call) {
        try {
            String name = call.getString("key");
            AtomicFile target = file(name);
            JSObject result = new JSObject();
            if (!target.getBaseFile().exists()) { result.put("value", JSONObject.NULL); call.resolve(result); return; }
            JSONObject record = new JSONObject(new String(target.readFully(), StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(record.getString("iv"), Base64.NO_WRAP)));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            result.put("value", new String(cipher.doFinal(Base64.decode(record.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
            call.resolve(result);
        } catch (Exception error) { call.reject("无法读取安全登录状态，请重新登录"); }
    }
    @PluginMethod public synchronized void set(PluginCall call) {
        AtomicFile target = null;
        FileOutputStream output = null;
        try {
            String name = call.getString("key"), value = call.getString("value");
            if (value == null || value.length() > 131072) throw new IllegalArgumentException();
            target = file(name);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            JSONObject record = new JSONObject();
            record.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            record.put("data", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
            output = target.startWrite();
            output.write(record.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
            call.resolve();
        } catch (Exception error) {
            if (target != null && output != null) target.failWrite(output);
            call.reject("无法安全保存登录状态");
        }
    }
    @PluginMethod public synchronized void remove(PluginCall call) {
        try { file(call.getString("key")).delete(); call.resolve(); }
        catch (Exception error) { call.reject("无法移除登录状态"); }
    }
}
