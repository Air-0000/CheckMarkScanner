package com.parking.checkscanner;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 端侧手写文字识别：PaddleOCR 中文识别模型 (assets 里的 .onnx，经 ONNX Runtime 运行，见 WASTE_MODELS)。
 * 只做单行识别，不做文字检测：输入是已按表格线切好的单个格子。ML Kit 读不懂潦草手写，这个模型明显更好。
 * 预处理 / 解码与 RapidOCR 一致：高 48、宽按比例 (至少 320) 补零，像素归一化到 [-1, 1]，CTC 贪心解码。
 */
public class PaddleTextRecognizer implements AutoCloseable {

    private static final int IMG_H = 48;
    private static final int MIN_W = 320;
    private static final int MAX_W = 1280;

    public static class Result {
        public final String text;
        public final float confidence;

        public Result(String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> characters;

    /**
     * 废料表用的识别模型 (assets 里的模型与字典)：PP-OCRv4 mobile 与 PP-OCRv6 small 两个一起用，
     * 每格各读一遍取更可信的结果。在同一张废料表的 150 格上：v4 单用对 128 格、v6 单用 138 格、两者合用 143 格 (App 内完整流程：v6 单用 137、合用 142)
     */
    public static final String[][] WASTE_MODELS = {
        {"ppocr_rec.onnx", "ppocr_keys.txt"},
        {"ppocr_v6s_rec.onnx", "ppocr_v6s_keys.txt"}
    };

    public PaddleTextRecognizer(Context context, String modelAsset, String keysAsset) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(readAsset(context, modelAsset), new OrtSession.SessionOptions());
        // 序号 0 为 CTC 空白，字典之后追加空格
        characters = new ArrayList<>();
        characters.add("");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                context.getAssets().open(keysAsset), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) characters.add(line);
        }
        characters.add(" ");
    }

    public Result recognize(Bitmap image) throws Exception {
        int w = image.getWidth();
        int h = image.getHeight();
        int resizedW = Math.max(1, Math.min(MAX_W, (int) Math.ceil(IMG_H * (float) w / h)));
        int inputW = Math.max(MIN_W, resizedW);

        Bitmap resized = Bitmap.createScaledBitmap(image, resizedW, IMG_H, true);
        int[] px = new int[resizedW * IMG_H];
        resized.getPixels(px, 0, resizedW, 0, 0, resizedW, IMG_H);
        if (resized != image) resized.recycle();

        // NCHW，通道顺序 B、G、R (模型按 OpenCV 读图训练)；右侧补零 (即归一化后的中灰)
        float[] data = new float[3 * IMG_H * inputW];
        int plane = IMG_H * inputW;
        for (int y = 0; y < IMG_H; y++) {
            for (int x = 0; x < resizedW; x++) {
                int p = px[y * resizedW + x];
                int i = y * inputW + x;
                data[i] = ((p & 0xFF) / 255f - 0.5f) / 0.5f;
                data[plane + i] = (((p >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
                data[2 * plane + i] = (((p >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
            }
        }

        String inputName = session.getInputNames().iterator().next();
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[] {1, 3, IMG_H, inputW});
             OrtSession.Result out = session.run(Collections.singletonMap(inputName, input))) {
            float[][][] probs = (float[][][]) out.get(0).getValue();
            return decode(probs[0]);
        }
    }

    /** CTC 贪心解码：逐帧取最大概率字符，去掉连续重复与空白；置信度为保留字符的平均概率 */
    private Result decode(float[][] steps) {
        StringBuilder sb = new StringBuilder();
        float confSum = 0;
        int kept = 0;
        int prev = -1;
        for (float[] step : steps) {
            int best = 0;
            for (int c = 1; c < step.length; c++) {
                if (step[c] > step[best]) best = c;
            }
            if (best != 0 && best != prev && best < characters.size()) {
                sb.append(characters.get(best));
                confSum += step[best];
                kept++;
            }
            prev = best;
        }
        return new Result(sb.toString(), kept > 0 ? confSum / kept : 0);
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream is = context.getAssets().open(name); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {}
    }
}
