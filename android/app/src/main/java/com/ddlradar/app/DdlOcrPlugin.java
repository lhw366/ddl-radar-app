package com.ddlradar.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 课表照片 OCR 桥。
 *
 * 用 Google ML Kit 中文文字识别（离线模型，随 APK 打包）。
 * 输出除了纯文本，还给出每个文本块的**包围盒坐标**——这是把 OCR 结果
 * 还原成「星期几 × 第几节」网格的关键：课表是二维表，只靠文字顺序
 * 无法判断某门课在星期几第几节，必须靠坐标落在哪一列/哪一行。
 *
 * JS 侧拿到 blocks 后按坐标聚类成行列，再映射成课程条目。
 */
@CapacitorPlugin(name = "DdlOcr")
public class DdlOcrPlugin extends Plugin {

    private TextRecognizer recognizer;

    private TextRecognizer getRecognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return recognizer;
    }

    /**
     * 识别一张图片。
     * 入参：{ base64: "data:image/jpeg;base64,..." } 或 { path: "file:///..." }
     * 出参：{ text: "全文", blocks: [{ text, left, top, right, bottom, lines: [...] }] }
     */
    @PluginMethod
    public void recognize(PluginCall call) {
        Bitmap bmp = null;
        try {
            String b64 = call.getString("base64");
            String path = call.getString("path");
            if (b64 != null && !b64.isEmpty()) {
                // 去掉 data URL 前缀
                int comma = b64.indexOf(',');
                if (comma >= 0) b64 = b64.substring(comma + 1);
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } else if (path != null && !path.isEmpty()) {
                Uri uri = Uri.parse(path);
                InputStream in;
                if ("content".equals(uri.getScheme())) {
                    in = getContext().getContentResolver().openInputStream(uri);
                } else {
                    in = new java.io.FileInputStream(new java.io.File(uri.getPath()));
                }
                bmp = BitmapFactory.decodeStream(in);
                if (in != null) in.close();
            }
            if (bmp == null) {
                call.reject("无法解码图片（base64 或 path 无效）");
                return;
            }
        } catch (Exception e) {
            call.reject("读取图片失败：" + msg(e));
            return;
        }

        final Bitmap finalBmp = bmp;
        InputImage image = InputImage.fromBitmap(finalBmp, 0);
        getRecognizer().process(image)
                .addOnSuccessListener(result -> {
                    try {
                        call.resolve(toJs(result, finalBmp));
                    } catch (Exception e) {
                        call.reject("解析识别结果失败：" + msg(e));
                    } finally {
                        try { finalBmp.recycle(); } catch (Exception ignore) {}
                    }
                })
                .addOnFailureListener(e -> {
                    try { finalBmp.recycle(); } catch (Exception ignore) {}
                    call.reject("识别失败：" + msg(e));
                });
    }

    /** 把 ML Kit 结果转成 JS 结构（含坐标，供前端做网格还原） */
    private JSObject toJs(Text result, Bitmap source) {
        JSObject out = new JSObject();
        out.put("text", result.getText());
        out.put("imageWidth", source.getWidth());
        out.put("imageHeight", source.getHeight());

        JSArray blocks = new JSArray();
        for (Text.TextBlock blk : result.getTextBlocks()) {
            android.graphics.Rect bb = blk.getBoundingBox();
            JSObject b = new JSObject();
            b.put("text", blk.getText());
            if (bb != null) {
                b.put("left", bb.left);
                b.put("top", bb.top);
                b.put("right", bb.right);
                b.put("bottom", bb.bottom);
                b.put("cx", (bb.left + bb.right) / 2);
                b.put("cy", (bb.top + bb.bottom) / 2);
            }
            JSArray lines = new JSArray();
            for (Text.Line ln : blk.getLines()) {
                android.graphics.Rect lb = ln.getBoundingBox();
                JSObject l = new JSObject();
                l.put("text", ln.getText());
                if (lb != null) {
                    l.put("left", lb.left);
                    l.put("top", lb.top);
                    l.put("right", lb.right);
                    l.put("bottom", lb.bottom);
                    l.put("cx", (lb.left + lb.right) / 2);
                    l.put("cy", (lb.top + lb.bottom) / 2);
                }
                lines.put(l);
            }
            b.put("lines", lines);
            blocks.put(b);
        }
        out.put("blocks", blocks);
        return out;
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    protected void handleOnDestroy() {
        try { if (recognizer != null) recognizer.close(); } catch (Exception ignore) {}
        super.handleOnDestroy();
    }
}
