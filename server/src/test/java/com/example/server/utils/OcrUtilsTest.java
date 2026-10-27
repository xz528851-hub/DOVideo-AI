package com.example.server.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrUtilsTest {

    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    @Test
    void keepsHighConfidenceLatinWordsAndInsertsSpaces() {
        String tsv = HEADER
                + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t95.5\tHELLO\n"
                + "5\t1\t1\t1\t1\t2\t12\t0\t10\t10\t90.0\tWORLD\n";

        OcrUtils.OcrResult result = OcrUtils.parseTsv(tsv, 40);

        assertTrue(result.accepted());
        assertEquals("HELLO WORLD", result.text());
        assertEquals(92.75, result.confidence(), 0.01);
        assertEquals(2, result.wordCount());
    }

    @Test
    void rejectsLowConfidenceText() {
        String tsv = HEADER
                + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t12.0\tXYZ\n";

        OcrUtils.OcrResult result = OcrUtils.parseTsv(tsv, 40);

        assertFalse(result.accepted());
        assertEquals("", result.text());
    }

    @Test
    void joinsChineseCharactersWithoutSpaces() {
        String tsv = HEADER
                + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t90\t前\n"
                + "5\t1\t1\t1\t1\t2\t10\t0\t10\t10\t90\t序\n"
                + "5\t1\t1\t1\t1\t3\t20\t0\t10\t10\t90\t遍\n"
                + "5\t1\t1\t1\t1\t4\t30\t0\t10\t10\t90\t历\n";

        OcrUtils.OcrResult result = OcrUtils.parseTsv(tsv, 40);

        assertTrue(result.accepted());
        assertEquals("前序遍历", result.text());
    }

    @Test
    void skipsMalformedRowsAndKeepsValidOnes() {
        String tsv = HEADER
                + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\tnot-a-number\tBAD\n"
                + "5\t1\t1\t1\t1\t2\t0\t0\t10\t10\t88\tGOOD\n";

        OcrUtils.OcrResult result = OcrUtils.parseTsv(tsv, 40);

        assertTrue(result.accepted());
        assertEquals("GOOD", result.text());
    }

    @Test
    void emptyOutputIsNotAccepted() {
        OcrUtils.OcrResult result = OcrUtils.parseTsv("", 40);

        assertFalse(result.accepted());
        assertEquals("", result.text());
    }
}
