package com.handcode.app.data.interpreter

import com.chaquo.python.Python
import com.handcode.app.data.parser.ChaquopyUnavailableException
import com.handcode.app.domain.model.CodeResult
import com.handcode.app.domain.repository.CodeExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chaquopy 기반 Python 인터프리터 구현체.
 *
 * `handcode_engine.execute_code(code)` 를 호출하고 반환 dict 를 CodeResult 로 매핑한다.
 *
 * Python 반환 dict (7주차):
 *   성공: { "success": true,  "output": str, "stderr": str,
 *           "error": null,    "error_type": null, "execution_time_ms": int }
 *   실패: { "success": false, "output": str, "stderr": str,
 *           "error": str,     "error_type": str,  "execution_time_ms": int }
 *
 * 7주차 변경점:
 *   - output / stderr 를 각각 분리해 CodeResult.Success 에 담는다.
 *     UI 에서 stdout 은 초록 블록, stderr 는 노랑 경고 블록으로 렌더한다.
 *   - error_type 을 활용해 SyntaxError / RuntimeError 를 정확히 매핑.
 */
@Singleton
class ChaquopyCodeExecutor @Inject constructor() : CodeExecutor {

    /**
     * 예외 처리 원칙 (ChaquopyCodeValidator 와 동일한 대칭 구조):
     *  - Python 런타임/모듈 로드 실패 → [ChaquopyUnavailableException] 으로 rethrow.
     *    ViewModel 쪽에서 ENGINE_UNAVAILABLE 분기로 처리한다.
     *  - [CancellationException] → 코루틴 취소 신호이므로 절대 삼키지 않고 그대로 재전파.
     *  - 그 외(= callAttr 호출이 실제로 일어났지만 dict 반환 전에 예외) → 사용자 코드 실행 중
     *    raise 된 것으로 간주하여 기존대로 [CodeResult.RuntimeError] 로 흡수.
     *
     * 이전에는 모든 Throwable 을 RuntimeError 로 흡수해서, 엔진 자체가 죽어도 사용자에게
     * "코드 실행 오류"로 잘못 안내되던 문제가 있었다.
     */
    override suspend fun execute(code: String): CodeResult =
        withContext(Dispatchers.Default) {
            val py = try {
                Python.getInstance()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw ChaquopyUnavailableException(
                    message = "Python 런타임을 불러오지 못했어요",
                    cause = t
                )
            }

            val module = try {
                py.getModule(PYTHON_MODULE)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                throw ChaquopyUnavailableException(
                    message = "handcode_engine 모듈을 불러오지 못했어요",
                    cause = t
                )
            }

            try {
                val raw = module.callAttr(FN_EXECUTE, code)
                val map = raw.toJava(Map::class.java) as Map<*, *>
                mapToCodeResult(map)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 여기까지 왔다면 Python 측에서 예외가 났지만 dict 반환 자체는 실패한 경우.
                // 사용자 코드 실행 중 발생한 런타임 에러로 간주한다.
                CodeResult.RuntimeError(
                    message = t.message ?: "실행 중 오류"
                )
            }
        }

    private fun mapToCodeResult(map: Map<*, *>): CodeResult {
        val success = (map[KEY_SUCCESS] as? Boolean) ?: false
        val output = (map[KEY_OUTPUT] as? String).orEmpty()
        val stderr = (map[KEY_STDERR] as? String).orEmpty()
        val error = map[KEY_ERROR] as? String
        val errorType = map[KEY_ERROR_TYPE] as? String
        val executionMs = asLong(map[KEY_EXECUTION_MS])

        if (success) {
            return CodeResult.Success(
                output = output,
                stderr = stderr,
                executionTimeMs = executionMs
            )
        }

        // 실패 — error_type 우선, 없으면 메시지 키워드로 보수적으로 판정.
        val message = error ?: "코드 실행에 실패했어요"
        val isSyntax = errorType == TYPE_SYNTAX_ERROR ||
            (errorType == null && message.contains(TYPE_SYNTAX_ERROR, ignoreCase = true))

        return if (isSyntax) {
            CodeResult.SyntaxError(
                line = resolveErrorLine(map, message),
                message = message
            )
        } else {
            CodeResult.RuntimeError(message = message)
        }
    }

    /**
     * 에러 라인 번호 결정 로직 (우선순위):
     *   1) Python dict 의 error_line 키 (Int/Long/String 허용)
     *   2) errors[0]["line"] (validate_syntax 반환 구조)
     *   3) error 메시지/트레이스백에서 "line N" 정규식 추출
     *   4) 모두 실패 시 DEFAULT_ERROR_LINE (=1)
     */
    private fun resolveErrorLine(map: Map<*, *>, message: String): Int {
        asInt(map[KEY_ERROR_LINE])?.let { return it }

        (map[KEY_ERRORS] as? List<*>)?.firstOrNull()?.let { first ->
            (first as? Map<*, *>)?.get(KEY_LINE)?.let { asInt(it)?.let { n -> return n } }
        }

        LINE_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }

        return DEFAULT_ERROR_LINE
    }

    /** Chaquopy 숫자는 Int/Long/문자열로 올 수 있어 공통 변환. */
    private fun asInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    /** Chaquopy 가 숫자를 Integer 로 줄 수도 있어 Number 공통 처리. */
    private fun asLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        else -> 0L
    }

    companion object {
        private const val PYTHON_MODULE = "handcode_engine"
        private const val FN_EXECUTE = "execute_code"

        private const val KEY_SUCCESS = "success"
        private const val KEY_OUTPUT = "output"
        private const val KEY_STDERR = "stderr"
        private const val KEY_ERROR = "error"
        private const val KEY_ERROR_TYPE = "error_type"
        private const val KEY_EXECUTION_MS = "execution_time_ms"
        private const val KEY_ERROR_LINE = "error_line"
        private const val KEY_ERRORS = "errors"
        private const val KEY_LINE = "line"

        private const val TYPE_SYNTAX_ERROR = "SyntaxError"
        private const val DEFAULT_ERROR_LINE = 1

        // "line 12", "Line 3" 같은 포맷에서 숫자만 추출.
        // Python traceback 은 보통 'File "<handcode>", line N' 형태.
        private val LINE_REGEX = Regex("""line\s+(\d+)""", RegexOption.IGNORE_CASE)
    }
}
