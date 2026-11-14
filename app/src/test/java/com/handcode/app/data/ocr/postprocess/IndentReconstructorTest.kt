package com.handcode.app.data.ocr.postprocess

import com.handcode.app.domain.model.RecognizedBlock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `IndentReconstructor` TDD 테스트 — Stanford codeocr (arXiv 2408.07220, MIT) Meanshift 1D
 * 알고리즘 포팅의 실패-먼저 명세.
 *
 * 각 테스트는 블록의 (top, left) 좌표만 다르게 하여 들여쓰기 레벨 복원 동작을 고립 검증한다.
 */
class IndentReconstructorTest {

    private fun block(text: String, top: Int, left: Int, height: Int = 30) =
        RecognizedBlock(
            text = text,
            confidence = 0f,
            top = top,
            left = left,
            width = 200,
            height = height
        )

    @Test fun emptyInput_returnsEmptyResult() {
        val r = IndentReconstructor.reconstruct(emptyList())
        assertEquals("", r.text)
        assertEquals(emptyList<Int>(), r.levels)
    }

    @Test fun singleLine_levelZero() {
        val r = IndentReconstructor.reconstruct(listOf(block("print(1)", 0, 10)))
        assertEquals("print(1)", r.text)
        assertEquals(listOf(0), r.levels)
    }

    @Test fun twoLevelIndent_defBody() {
        val r = IndentReconstructor.reconstruct(
            listOf(
                block("def f():", 0, 10),
                block("return 1", 40, 60)
            )
        )
        assertEquals("def f():\n    return 1", r.text)
        assertEquals(listOf(0, 1), r.levels)
    }

    @Test fun threeLevelIndent_nestedLoop() {
        val r = IndentReconstructor.reconstruct(
            listOf(
                block("def outer():", 0, 10),
                block("for i in range(3):", 40, 60),
                block("print(i)", 80, 110)
            )
        )
        assertEquals(
            "def outer():\n    for i in range(3):\n        print(i)",
            r.text
        )
    }

    @Test fun outOfOrder_ySortedFirst() {
        val r = IndentReconstructor.reconstruct(
            listOf(
                block("print(i)", 80, 110),
                block("def outer():", 0, 10),
                block("for i in range(3):", 40, 60)
            )
        )
        assertEquals(
            "def outer():\n    for i in range(3):\n        print(i)",
            r.text
        )
    }

    @Test fun noise_within_bandwidth_mergesCluster() {
        val r = IndentReconstructor.reconstruct(
            listOf(
                block("x = 1", 0, 10),
                block("y = 2", 40, 14),
                block("z = 3", 80, 8)
            )
        )
        assertEquals("x = 1\ny = 2\nz = 3", r.text)
        assertEquals(listOf(0, 0, 0), r.levels)
    }

    @Test fun zeroHeight_fallbackToDefaultBandwidth() {
        val r = IndentReconstructor.reconstruct(
            listOf(
                block("a = 1", 0, 10, height = 0),
                block("b = 2", 40, 12, height = 0)
            )
        )
        assertEquals(listOf(0, 0), r.levels)
    }
}
