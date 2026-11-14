package com.handcode.app.data.ocr.postprocess

import com.handcode.app.domain.model.RecognizedBlock
import kotlin.math.abs

/**
 * ML Kit `textBlocks` 의 x 좌표를 Meanshift 1D 클러스터링해 각 라인의 들여쓰기 레벨을 산출하고,
 * 레벨 × 4 공백으로 텍스트를 재조립한다.
 *
 * 레퍼런스: Stanford codeocr (arXiv 2408.07220, `github.com/mdoumbouya/codeocr`, MIT) §3.2 —
 * 손글씨 Python 코드의 들여쓰기 복원 문제. 우리는 1D(x좌표)만 쓰고 bandwidth 를 블록 높이로
 * 적응 추정해, 펜 굵기/글씨 크기가 달라도 안정적으로 레벨을 뽑아낸다.
 *
 * 알고리즘:
 *  1) 입력 블록을 y(top) 오름차순 정렬 → 자연스런 코드 라인 순서 확보
 *  2) 각 라인의 left 값을 1D 점 집합으로 본다
 *  3) bandwidth = 평균 블록 높이 × 0.6 (높이 0 이면 [DEFAULT_BANDWIDTH_PX])
 *  4) 각 점을 Meanshift 로 수렴시켜 클러스터 중심을 찾는다
 *  5) 중심들을 x 오름차순 정렬 → 레벨 번호 0, 1, 2… 부여
 *  6) 라인 = `" ".repeat(level * 4) + block.text` 로 재조립, `\n` join
 */
object IndentReconstructor {

    private const val INDENT_WIDTH = 4

    /** 블록 높이가 0/음수일 때 사용하는 폴백 bandwidth. 일반 손글씨 한 줄 높이 대략값. */
    private const val DEFAULT_BANDWIDTH_PX = 24.0

    /** 평균 블록 높이에 곱해 bandwidth 를 정하는 비율. codeocr §3.2 에서 제안된 heuristic. */
    private const val BANDWIDTH_HEIGHT_RATIO = 0.6

    /** Meanshift 수렴 판정 역치 (픽셀). 이보다 이동이 작으면 안정 수렴으로 본다. */
    private const val CONVERGENCE_EPSILON = 0.5

    /** 수렴 실패 대비 iteration 상한 — bandwidth 가 크면 보통 5회 이내 수렴. */
    private const val MAX_ITERATIONS = 50

    data class Result(val text: String, val levels: List<Int>)

    fun reconstruct(blocks: List<RecognizedBlock>): Result {
        if (blocks.isEmpty()) return Result("", emptyList())
        val sorted = blocks.sortedBy { it.top }
        val lefts = sorted.map { it.left.toDouble() }
        val bandwidth = estimateBandwidth(sorted)
        val centers = lefts.map { shiftUntilConverged(it, lefts, bandwidth) }
        val distinctCenters = centers.distinct().sorted()
        val levels = centers.map { c ->
            distinctCenters.indexOfFirst { abs(it - c) < CONVERGENCE_EPSILON }
        }
        val text = sorted.zip(levels) { b, lvl ->
            " ".repeat(lvl * INDENT_WIDTH) + b.text
        }.joinToString("\n")
        return Result(text, levels)
    }

    private fun estimateBandwidth(blocks: List<RecognizedBlock>): Double {
        val avg = blocks.map { it.height }.average()
        return if (avg.isNaN() || avg <= 0.0) DEFAULT_BANDWIDTH_PX
        else avg * BANDWIDTH_HEIGHT_RATIO
    }

    private fun shiftUntilConverged(start: Double, all: List<Double>, bandwidth: Double): Double {
        var current = start
        repeat(MAX_ITERATIONS) {
            val inRange = all.filter { abs(it - current) <= bandwidth }
            if (inRange.isEmpty()) return current
            val next = inRange.average()
            if (abs(next - current) < CONVERGENCE_EPSILON) return next
            current = next
        }
        return current
    }
}
