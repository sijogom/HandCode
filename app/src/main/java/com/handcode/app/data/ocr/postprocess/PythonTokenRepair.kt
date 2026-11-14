package com.handcode.app.data.ocr.postprocess

/**
 * OCR 결과에 자주 섞이는 Python 토큰 오인식을 규칙 기반으로 감지해 [Suggestion] 목록을 만든다.
 *
 * 자동 교체는 **하지 않는다**. 사용자가 편집 화면의 `QuickFixPanel` 에서 탭해야만 반영된다 —
 * 변수명 변조 위험(예: `l ↔ 1`, `O ↔ 0`) 을 피하기 위한 설계. 대신 자주 등장하는 패턴 4종을
 * 잡아 제안만 띄운다.
 *
 * 포함 규칙:
 *  - **MISSING_COLON**: `if/elif/else/for/while/def/class/try/except/finally/with` 로 시작하는데
 *    주석 제외 후 `:` 로 끝나지 않는 라인
 *  - **UNBALANCED_PAREN**: 한 라인 내 `(` / `)` 개수 불일치 (공백·주석 제외)
 *  - **UNBALANCED_QUOTE**: 한 라인 내 `'` 또는 `"` 가 홀수 개
 *  - **KEYWORD_TYPO**: OCR 가 자주 틀리는 Python 키워드 오타
 *
 * 의도적으로 **빠진** 것 (변조 리스크가 커서 제안조차 안 함):
 *  - 알파벳 혼동 (`l ↔ 1`, `O ↔ 0`): 변수명이 달라져 의미 변경
 *  - 들여쓰기 보정: `IndentReconstructor` 담당
 *  - 전각/스마트 기호: `CodeTextCorrector` 담당
 */
object PythonTokenRepair {

    private val BLOCK_KEYWORDS = setOf(
        "if", "elif", "else", "for", "while", "def", "class",
        "try", "except", "finally", "with"
    )

    /**
     * 자주 등장하는 OCR 키워드 오타 테이블. 학생 손글씨 Python 에서 숫자/특수문자 혼동이 많음.
     * F1 베이스라인 실측에서 새 패턴이 보이면 여기 추가.
     */
    private val KEYWORD_TYPO_TABLE = mapOf(
        "pr1nt" to "print",
        "pr1n+" to "print",
        "retvrn" to "return",
        "irnport" to "import",
        "c1ass" to "class"
    )

    /**
     * Python 문법상 **반드시 소문자**로 써야 하는 식별자들 (키워드 + 자주 쓰는 빌트인 함수).
     *
     * 손글씨 OCR 에서 P/p, T/t 같은 대소문자 혼동이 자주 발생하지만 사전 자동 교체는 위험
     * (변수명 변조). 대신 이 set 의 lowercase 와 일치하는데 원본 표기가 다르면 제안만 띄움 →
     * 사용자가 QuickFixPanel 에서 직접 탭해서 수정. 알고리즘 기반이라 `Print/PRINT/pRiNt` 같은
     * 모든 변형을 한 번에 잡음 (사전 하드코딩 X).
     */
    private val PYTHON_LOWERCASE_REQUIRED = setOf(
        // 예약어
        "if", "elif", "else", "for", "while", "def", "class",
        "return", "import", "from", "as", "try", "except", "finally",
        "with", "and", "or", "not", "in", "is", "lambda", "pass",
        "break", "continue", "global", "nonlocal", "yield", "raise",
        "assert", "del", "async", "await",
        // 자주 쓰이는 빌트인 함수
        "print", "len", "range", "int", "str", "float", "bool",
        "list", "dict", "tuple", "set", "abs", "min", "max", "sum",
        "sorted", "reversed", "enumerate", "zip", "map", "filter",
        "round", "divmod", "pow", "isinstance", "type", "super",
        "input", "open", "iter", "next", "id", "hash", "repr",
        "callable", "chr", "ord", "hex", "oct", "bin", "format",
        "vars", "dir", "all", "any"
    )

    /**
     * Python 문법상 **첫 글자 대문자** 로 써야 하는 식별자들 (리터럴 + 표준 예외).
     *
     * `True/False/None` 은 키워드, 예외 클래스들은 PascalCase 가 PEP 8 표준. 손글씨에서
     * `true/false/none` 으로 인식되거나 `valueerror/Exception` 식으로 표기 흔들리는 케이스 잡음.
     */
    private val PYTHON_TITLECASE_REQUIRED = setOf(
        "True", "False", "None",
        "Exception", "BaseException", "ValueError", "TypeError",
        "KeyError", "IndexError", "RuntimeError", "AttributeError",
        "NameError", "ImportError", "ZeroDivisionError",
        "FileNotFoundError", "StopIteration", "OverflowError",
        "ArithmeticError", "AssertionError", "NotImplementedError",
        "RecursionError", "SystemExit", "KeyboardInterrupt",
        "UnicodeError", "LookupError", "MemoryError"
    )

    fun analyze(code: String): List<Suggestion> {
        if (code.isBlank()) return emptyList()
        val lines = code.split('\n')
        val result = mutableListOf<Suggestion>()
        lines.forEachIndexed { idx, line ->
            result += analyzeMissingColon(idx, line)
            result += analyzeUnbalancedParen(idx, line)
            result += analyzeUnbalancedQuote(idx, line)
            result += analyzeKeywordTypo(idx, line)
            result += analyzeBuiltinCaseMismatch(idx, line)
        }
        return result
    }

    private fun analyzeMissingColon(lineNo: Int, line: String): List<Suggestion> {
        val trimmed = line.trimStart()
        val firstToken = trimmed.substringBefore(' ').trimEnd(':')
        val firstTokenBeforeParen = trimmed.substringBefore('(').trimEnd(':')
        if (firstToken !in BLOCK_KEYWORDS && firstTokenBeforeParen !in BLOCK_KEYWORDS) {
            return emptyList()
        }
        val codeOnly = stripComment(line).trimEnd()
        if (codeOnly.endsWith(':')) return emptyList()
        val suffix = lineCommentSuffix(line)
        val replacement = if (suffix.isNotEmpty()) "$codeOnly:  $suffix" else "$codeOnly:"
        return listOf(
            Suggestion(
                line = lineNo,
                category = SuggestionCategory.MISSING_COLON,
                originalLine = line,
                replacement = replacement,
                message = "블록 키워드 뒤에 ':' 가 빠진 것 같습니다."
            )
        )
    }

    private fun analyzeUnbalancedParen(lineNo: Int, line: String): List<Suggestion> {
        val stripped = stripComment(line).filter { it != ' ' }
        val opens = stripped.count { it == '(' }
        val closes = stripped.count { it == ')' }
        if (opens == closes) return emptyList()
        val replacement = when {
            opens > closes -> line.trimEnd() + ")".repeat(opens - closes)
            else -> "(".repeat(closes - opens) + line.trimStart()
        }
        return listOf(
            Suggestion(
                line = lineNo,
                category = SuggestionCategory.UNBALANCED_PAREN,
                originalLine = line,
                replacement = replacement,
                message = "괄호 개수가 맞지 않습니다. (열림 $opens / 닫힘 $closes)"
            )
        )
    }

    private fun analyzeUnbalancedQuote(lineNo: Int, line: String): List<Suggestion> {
        val result = mutableListOf<Suggestion>()
        if (line.count { it == '\'' } % 2 != 0) {
            result += Suggestion(
                line = lineNo,
                category = SuggestionCategory.UNBALANCED_QUOTE,
                originalLine = line,
                replacement = line.trimEnd() + "'",
                message = "작은 따옴표가 짝을 이루지 않습니다."
            )
        }
        if (line.count { it == '"' } % 2 != 0) {
            result += Suggestion(
                line = lineNo,
                category = SuggestionCategory.UNBALANCED_QUOTE,
                originalLine = line,
                replacement = line.trimEnd() + "\"",
                message = "큰 따옴표가 짝을 이루지 않습니다."
            )
        }
        return result
    }

    private fun analyzeKeywordTypo(lineNo: Int, line: String): List<Suggestion> {
        var replaced = line
        var matched = false
        KEYWORD_TYPO_TABLE.forEach { (typo, fix) ->
            if (replaced.contains(typo)) {
                replaced = replaced.replace(typo, fix)
                matched = true
            }
        }
        if (!matched) return emptyList()
        return listOf(
            Suggestion(
                line = lineNo,
                category = SuggestionCategory.KEYWORD_TYPO,
                originalLine = line,
                replacement = replaced,
                message = "자주 오인식되는 키워드를 교정했습니다."
            )
        )
    }

    /**
     * Python 빌트인 / 키워드의 **대소문자 미스매치** 검사. 손글씨 OCR 에서 P/p, T/t 같은
     * 혼동으로 `Print`, `True` 같이 잘못된 표기가 들어왔을 때 제안만 띄움.
     *
     * 검사 원칙:
     *  - 주석 / 문자열 리터럴 안의 단어는 무시 (사용자가 의도해서 쓴 문자열일 수 있음)
     *  - 토큰 경계 정확히 잡아 `MyPrint` 같은 사용자 식별자는 제외
     *  - 자동 변환 X — 사용자가 [적용] 탭하면 단어 경계 첫 발생만 정확히 치환
     *  - 변수명과 충돌 가능성 (`Print = 1`) → false positive 라도 사용자가 무시 가능
     */
    private fun analyzeBuiltinCaseMismatch(lineNo: Int, line: String): List<Suggestion> {
        val codeOnly = stripStrings(stripComment(line))
        val tokenRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Suggestion>()

        for (match in tokenRegex.findAll(codeOnly)) {
            val token = match.value
            if (token in seen) continue
            seen.add(token)

            val correct = findExpectedCase(token) ?: continue
            if (token == correct) continue

            // 단어 경계 첫 발생만 치환 (전체 replace 는 부분 일치까지 건드릴 위험).
            val replacement = Regex("\\b" + Regex.escape(token) + "\\b").replaceFirst(line, correct)
            if (replacement == line) continue

            result.add(
                Suggestion(
                    line = lineNo,
                    category = SuggestionCategory.KEYWORD_TYPO,
                    originalLine = line,
                    replacement = replacement,
                    message = "Python 표기는 '$correct' 입니다. ('$token' → '$correct')"
                )
            )
        }
        return result
    }

    /**
     * 토큰을 lowercase / titlecase set 과 대조해 정확한 표기를 찾는다. 없으면 null.
     * - `Print` / `PRINT` → "print"
     * - `true` / `TRUE` → "True"
     * - `valueerror` / `VALUEERROR` → "ValueError"
     */
    private fun findExpectedCase(token: String): String? {
        val lower = token.lowercase()
        if (lower in PYTHON_LOWERCASE_REQUIRED) return lower
        return PYTHON_TITLECASE_REQUIRED.firstOrNull { it.equals(token, ignoreCase = true) }
    }

    /**
     * 문자열 리터럴 ('...' / "...") 안의 문자를 모두 공백으로 치환. 토큰 추출 시
     * 문자열 안의 단어가 잡히지 않도록 한다. 따옴표 매칭은 [findCommentStart] 와 동일한 방식.
     */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in line) {
            when {
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    sb.append(' ')
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    sb.append(' ')
                }
                inSingle || inDouble -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun stripComment(line: String): String {
        val idx = findCommentStart(line)
        return if (idx < 0) line else line.substring(0, idx)
    }

    private fun lineCommentSuffix(line: String): String {
        val idx = findCommentStart(line)
        return if (idx < 0) "" else line.substring(idx)
    }

    /**
     * Python `#` 주석 시작 위치. 단, 문자열 리터럴 안의 `#` 은 주석이 아니므로 제외.
     */
    private fun findCommentStart(line: String): Int {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble) return i
            }
        }
        return -1
    }
}
