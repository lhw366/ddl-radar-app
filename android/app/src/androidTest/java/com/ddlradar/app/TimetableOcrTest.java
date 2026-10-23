package com.ddlradar.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual bundled OCR, not invented text boxes. The host checks all 13 course placements. */
@RunWith(AndroidJUnit4.class)
public class TimetableOcrTest {
    @Test public void recognizeReferenceImage() throws Exception {
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source;
        try (java.io.InputStream input = test.getAssets().open("timetable-reference.jpg")) {
            source = BitmapFactory.decodeStream(input);
        }
        assertNotNull(source);
        TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(source, 0)), 60, TimeUnit.SECONDS);
            String json = DdlOcrPlugin.toJs(result, source).toString();
            try (FileOutputStream output = new FileOutputStream(new File(app.getFilesDir(), "timetable-ocr.json"))) {
                output.write(json.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(result.getTextBlocks().size() > 10);
        } finally { recognizer.close(); source.recycle(); }
    }
}
