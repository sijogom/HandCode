package com.handcode.app.data.parser

import com.chaquo.python.Python
import com.handcode.app.domain.model.SyntaxErrorInfo
import com.handcode.app.domain.model.ValidationResult
import com.handcode.app.domain.repository.CodeValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chaquopy 에 번들된 `handcode_engine` 파이썬 모듈의 `validate_syntax` 를 호출해
 * 파이썬 AST 기반 문법 검증을 수행한다.
 *
 * Python 반환 dict 구조:
 *   { "valid": bool, "errors": [{"line": int, "column": int, "message": str}] }
 *
 * 예외 처리 원칙:
 * - 검증이 "성공적으로 수행"되어 문법 오류 목록이 돌아오는 경우만 [ValidationResult] 로 반환.
 * - Python 인터프리터 자체가 기동되지 못했거나 모듈 로드에 실패한 경우는
 *   [ChaquopyUnavailableException] 으로 rethrow 하여 상위(ViewModel)에서 엔진 장애로 처리하게 한다.
 *   (이전에는 ValidationResult 로 흡수해 사용자에게 "문법 오류"로 잘못 안내되던 버그를 방지.)
 */
@Singleton
class ChaquopyCodeValidator @Inject constructor() : CodeValidator {

    // Python 모듈 로드는 비용이 있으므로 지연 초기화 후 재사용.
    private val engineModule by lazy {
        Python.getInstance().getModule(PYTHON_MODULE)
    }

    override suspend fun validate(code: String): ValidationResult =
        withContext(Dispatchers.Default) {
            try {
                val raw = engineModule.callAttr(FN_VALIDATE, code)
                // PyObject -> Java Map (키는 항상 문자열, 값은 섞여 있음).
                val javaMap = raw.toJava(Map::class.java) as Map<*, *>
                val validFlag = (javaMap["valid"] as? Boolean) ?: false
                @Suppress("UNCHECKED_CAST")
                val rawErrors = (javaMap["errors"] as? List<Map<String, Any?>>).orEmpty()

                ValidationResult(
                    isValid = validFlag,
                    errors = rawErrors.map { it.toSyntaxError() }
                )
            } catch (ce: CancellationException) {
                // 코루틴 취소는 절대 삼키지 않고 그대로 전파.
                throw ce
            } catch (throwable: Throwable) {
                // Python 호출 자체가 실패한 경우 (예: 모듈 로드 문제, 네이티브 예외, Python 미초기화 등).
                // 문법 오류로 위장하지 않고 엔진 장애로 rethrow → ViewModel 이 ENGINE_UNAVAILABLE 분기.
                throw ChaquopyUnavailableException(
                    message = throwable.message ?: "Python 인터프리터를 사용할 수 없습니다",
                    cause = throwable
                )
            }
        }

    private fun Map<String, Any?>.toSyntaxError(): SyntaxErrorInfo {
        val line = (this["line"] as? Number)?.toInt() ?: 1
        val column = (this["column"] as? Number)?.toInt() ?: 0
        val message = (this["message"] as? String) ?: "문법 오류"
        return SyntaxErrorInfo(line = line, column = column, message = message)
    }

    companion object {
        private const val PYTHON_MODULE = "handcode_engine"
        private const val FN_VALIDATE = "validate_syntax"
    }
}

/**
 * Chaquopy/Python 엔진 자체가 기동되지 못했을 때 발생하는 예외.
 * 사용자 코드의 문법 오류(ValidationResult.isValid=false)와 구분하기 위한 전용 타입.
 */
class ChaquopyUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
