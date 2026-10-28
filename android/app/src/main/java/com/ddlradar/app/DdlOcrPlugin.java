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
    static JSObject toJs(Text result, Bitmap source) {
        JSObject out = new JSObject();
        out.put("text", result.getText());
        out.put("imageWidth", source.getWidth());
        out.put("imageHeight", source.getHeight());
        RasterCells cells = new RasterCells(source);

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
                cells.attach(b, bb);
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
                    cells.attach(l, lb);
                }
                JSArray elements = new JSArray();
                for (Text.Element element : ln.getElements()) {
                    android.graphics.Rect eb = element.getBoundingBox();
                    JSObject e = new JSObject();
                    e.put("text", element.getText());
                    if (eb != null) {
                        e.put("left", eb.left); e.put("top", eb.top);
                        e.put("right", eb.right); e.put("bottom", eb.bottom);
                        e.put("cx", (eb.left + eb.right) / 2); e.put("cy", (eb.top + eb.bottom) / 2);
                        cells.attach(e, eb);
                    }
                    elements.put(e);
                }
                l.put("elements", elements);
                lines.put(l);
            }
            b.put("lines", lines);
            blocks.put(b);
        }
        out.put("blocks", blocks);
        return out;
    }

    /** Read colored card boundaries from the actual pixels, never from a course template. */
    private static final class RasterCells {
        final int width, height, scale, paper;
        final int[] pixels, labels, queue;
        final List<android.graphics.Rect> regions = new ArrayList<>();
        RasterCells(Bitmap bitmap) {
            scale = Math.max(1, (bitmap.getWidth() + 899) / 900);
            width = Math.max(1, bitmap.getWidth() / scale);
            height = Math.max(1, bitmap.getHeight() / scale);
            Bitmap small = Bitmap.createScaledBitmap(bitmap, width, height, false);
            pixels = new int[width * height]; small.getPixels(pixels, 0, width, 0, 0, width, height);
            if (small != bitmap) small.recycle();
            labels = new int[pixels.length]; queue = new int[pixels.length];
            int[] histogram = new int[4096];
            for (int i = 0; i < pixels.length; i += 7) {
                int color = pixels[i], r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                if (Math.max(r, Math.max(g, b)) > 220) histogram[(r >> 4) * 256 + (g >> 4) * 16 + (b >> 4)]++;
            }
            int best = 0;
            for (int i = 1; i < histogram.length; i++) if (histogram[i] > histogram[best]) best = i;
            long red = 0, green = 0, blue = 0; int count = 0;
            for (int i = 0; i < pixels.length; i += 7) {
                int color = pixels[i], r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                if (((r >> 4) * 256 + (g >> 4) * 16 + (b >> 4)) == best) { red += r; green += g; blue += b; count++; }
            }
            paper = count > 0 ? ((int)(red / count) << 16) | ((int)(green / count) << 8) | (int)(blue / count) : 0xffffff;
            regions.add(null);
        }
        int distance(int a, int b) {
            return Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
                Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
        }
        android.graphics.Rect region(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return null;
            int seed = y * width + x, color = pixels[seed];
            if (distance(color, paper) < 13) return null;
            if (labels[seed] > 0) return regions.get(labels[seed]);
            if (labels[seed] < 0) return null;
            int number = regions.size(), start = 0, end = 0, minX = x, maxX = x, minY = y, maxY = y;
            queue[end++] = seed; labels[seed] = number;
            while (start < end) {
                int at = queue[start++], px = at % width, py = at / width;
                minX = Math.min(minX, px); maxX = Math.max(maxX, px);
                minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                int[] neighbors = {px > 0 ? at - 1 : -1, px + 1 < width ? at + 1 : -1,
                    py > 0 ? at - width : -1, py + 1 < height ? at + width : -1};
                for (int next : neighbors) if (next >= 0 && labels[next] == 0 && distance(pixels[next], color) <= 10) {
                    labels[next] = number; queue[end++] = next;
                }
            }
            int rw = maxX - minX + 1, rh = maxY - minY + 1;
            android.graphics.Rect bounds = rw >= 22 && rh >= 20 && end >= rw * rh * .55
                && rw < width * .8 && rh < height * .8
                ? new android.graphics.Rect(minX * scale, minY * scale, (maxX + 1) * scale, (maxY + 1) * scale) : null;
            regions.add(bounds);
            return bounds;
        }
        void attach(JSObject object, android.graphics.Rect text) {
            int[] xs = {text.left - 5, (text.left + text.right) / 2, text.right + 5};
            int[] ys = {text.top - 5, text.top + 2, (text.top + text.bottom) / 2};
            android.graphics.Rect best = null;
            for (int x : xs) for (int y : ys) {
                android.graphics.Rect candidate = region(x / scale, y / scale);
                if (candidate == null || !candidate.contains((text.left + text.right) / 2, (text.top + text.bottom) / 2)) continue;
                if (best == null || candidate.width() * candidate.height() < best.width() * best.height()) best = candidate;
            }
            if (best != null) {
                JSObject bounds = new JSObject();
                bounds.put("left", best.left); bounds.put("top", best.top);
                bounds.put("right", best.right); bounds.put("bottom", best.bottom);
                object.put("cell", bounds);
            }
        }
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
