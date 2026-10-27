package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class OcrUtils {

    private static final Logger log = LoggerFactory.getLogger(OcrUtils.class);

    private final String ocrCommand;
    private final int minConfidence;

    public OcrUtils(@Value("${tool.ocr.command:tesseract}") String ocrCommand,
                    @Value("${tool.ocr.min-confidence:40}") int minConfidence) {
        if (minConfidence < 0 || minConfidence > 100) {
            throw new IllegalArgumentException("tool.ocr.min-confidence must be between 0 and 100");
        }
        this.ocrCommand = ocrCommand;
        this.minConfidence = minConfidence;
    }

    public String recognize(File image) {
        if (image == null || !image.isFile()) throw new IllegalArgumentException("OCR image does not exist");
        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile("dovideo-ocr-", ".tsv");
            // TSV 输出保留每个词的置信度；stdout 作为输出基名，tsv 作为 config 输出表格。
            process = new ProcessBuilder(
                    ocrCommand, image.getAbsolutePath(), "stdout", "-l", "chi_sim+eng", "tsv")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR execution timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("OCR process failed with exit code " + process.exitValue());
            }
            OcrResult result = parseTsv(Files.readString(output, StandardCharsets.UTF_8), minConfidence);
            if (!result.accepted()) {
                log.warn("ocr_low_confidence image={} meanConfidence={} threshold={} words={}",
                        image.getName(),
                        String.format(Locale.ROOT, "%.2f", result.confidence()),
                        minConfidence,
                        result.wordCount());
                return "";
            }
            return result.text();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR execution interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR failed for " + image.getName(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception e) {
                    log.warn("ocr_output_cleanup_failed path={}", output, e);
                }
            }
        }
    }

    /**
     * 解析 Tesseract TSV。level=5 是词级别；conf<0 表示非文本行。
     * 低于阈值的整帧文本直接丢弃，避免乱码进入证据池。
     */
    static OcrResult parseTsv(String tsv, int minConfidence) {
        if (tsv == null || tsv.isBlank()) return OcrResult.empty();

        List<OcrWord> words = new ArrayList<>();
        String[] rows = tsv.split("\\R");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            if (i == 0 && row.startsWith("level")) continue;
            String[] cols = row.split("\t", 12);
            if (cols.length < 12) continue;
            try {
                if (Integer.parseInt(cols[0].trim()) != 5) continue;
                String text = cols[11].replace("\r", "").replace("\n", "").trim();
                if (text.isEmpty()) continue;
                double confidence = Double.parseDouble(cols[10].trim());
                if (confidence < 0) continue;
                words.add(new OcrWord(
                        Integer.parseInt(cols[2].trim()),
                        Integer.parseInt(cols[3].trim()),
                        Integer.parseInt(cols[4].trim()),
                        text,
                        confidence));
            } catch (NumberFormatException ignored) {
                // 跳过格式异常的行，不让单行坏数据毁掉整帧识别。
            }
        }
        if (words.isEmpty()) return OcrResult.empty();

        double mean = words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
        if (mean < minConfidence) return new OcrResult("", mean, words.size(), false);
        return new OcrResult(joinLines(words), mean, words.size(), true);
    }

    private static String joinLines(List<OcrWord> words) {
        StringBuilder result = new StringBuilder();
        StringBuilder line = new StringBuilder();
        OcrWord previous = null;
        for (OcrWord word : words) {
            boolean newLine = previous == null
                    || word.block() != previous.block()
                    || word.paragraph() != previous.paragraph()
                    || word.line() != previous.line();
            if (newLine && line.length() > 0) {
                result.append(line).append('\n');
                line.setLength(0);
            }
            appendWord(line, word.text());
            previous = word;
        }
        if (line.length() > 0) result.append(line);
        return result.toString().trim();
    }

    /** 中文词之间不加空格；拉丁词之间保留空格。 */
    private static void appendWord(StringBuilder line, String word) {
        if (line.length() == 0) {
            line.append(word);
            return;
        }
        char previous = line.charAt(line.length() - 1);
        char next = word.charAt(0);
        if (isCjk(previous) && isCjk(next)) {
            line.append(word);
        } else {
            line.append(' ').append(word);
        }
    }

    private static boolean isCjk(char value) {
        return value >= '\u4e00' && value <= '\u9fff';
    }

    record OcrResult(String text, double confidence, int wordCount, boolean accepted) {
        static OcrResult empty() {
            return new OcrResult("", 0, 0, false);
        }
    }

    private record OcrWord(int block, int paragraph, int line, String text, double confidence) {
    }
}
