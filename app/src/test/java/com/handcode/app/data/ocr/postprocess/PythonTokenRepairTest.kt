package com.handcode.app.data.ocr.postprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PythonTokenRepair` TDD 테스트 — OCR 결과에 자주 섞이는 콜론 누락·괄호 불균형·따옴표 불균형·
 * 키워드 타이포를 규칙 기반으로 감지해 [Suggestion] 목록을 만드는지 검증.
 *
 * 자동 교체가 아니라 제안 생성만 담당. 사용자가 탭해야 반영 → 변수명 변조 위험 없음.
 */
class PythonTokenRepairTest {

    @Test fun emptyCode_noSuggestions() {
        assertEquals(emptyList<Suggestion>(), PythonTokenRepair.analyze(""))
    }

    @Test fun missingColon_afterIf() {
        val s = PythonTokenRepair.analyze("if x > 0\n    print(x)")
            .first { it.category == SuggestionCategory.MISSING_COLON }
        assertEquals(0, s.line)
        assertEquals("if x > 0:", s.replacement)
    }

    @Test fun missingColon_afterDef() {
        val s = PythonTokenRepair.analyze("def foo(a, b)\n    return a + b")
            .first { it.category == SuggestionCategory.MISSING_COLON }
        assertEquals("def foo(a, b):", s.replacement)
    }

    @Test fun noFalsePositive_colonAlreadyPresent() {
        assertTrue(
            PythonTokenRepair.analyze("if x > 0:\n    print(x)")
                .none { it.category == SuggestionCategory.MISSING_COLON }
        )
    }

    @Test fun unbalancedParen_moreOpen() {
        val s = PythonTokenRepair.analyze("result = func(a, b")
            .first { it.category == SuggestionCategory.UNBALANCED_PAREN }
        assertEquals("result = func(a, b)", s.replacement)
    }

    @Test fun unbalancedQuote_oddSingle() {
        val s = PythonTokenRepair.analyze("name = 'Alice")
            .first { it.category == SuggestionCategory.UNBALANCED_QUOTE }
        assertEquals("name = 'Alice'", s.replacement)
    }

    @Test fun keywordTypo_pr1nt() {
        val s = PythonTokenRepair.analyze("pr1nt('hello')")
            .first { it.category == SuggestionCategory.KEYWORD_TYPO }
        assertEquals("print('hello')", s.replacement)
    }

    @Test fun commentOnly_noSuggestion() {
        assertEquals(emptyList<Suggestion>(), PythonTokenRepair.analyze("# just a comment"))
    }

    @Test fun nestedParens_balanced_noSuggestion() {
        assertTrue(
            PythonTokenRepair.analyze("print(f(g(x)))")
                .none { it.category == SuggestionCategory.UNBALANCED_PAREN }
        )
    }

    @Test fun commentAfterMissingColon_stillDetects() {
        val s = PythonTokenRepair.analyze("if x > 0  # compare\n    pass")
            .first { it.category == SuggestionCategory.MISSING_COLON }
        assertTrue(s.replacement.startsWith("if x > 0:"))
    }
}
