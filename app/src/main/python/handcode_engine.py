"""handcode_engine.py — Chaquopy 로 APK 에 번들되는 파이썬 모듈.

OCR 로 인식된 코드의 문법 검증, 보안 필터링, 실행을 담당한다.

반환 형식:
    validate_syntax(code) -> {"valid": bool, "errors": [{"line", "column", "message"}]}
    execute_code(code)    -> {"success": bool, "output": str,
                              "error": str | None,
                              "error_type": str | None,
                              "execution_time_ms": int}

7주차 변경점:
  - stdout / stderr 를 문자열 차원에서 합치지 않고 별도로 반환한다.
    ("Chaquopy stderr 순서 뒤집힘" 버그 대응. UI 에서 둘을 분리 렌더링한다.)
  - error_type 키를 추가했다. ("SyntaxError" / "ForbiddenModule" / 예외 클래스명 / "TimeoutError")
"""
import ast
import io
import time
import traceback
import contextlib


# Chaquopy p2j() 는 Python dict/list 를 Java Map/List 로 자동 변환하지 않으므로
# 반환 직전에 명시적으로 HashMap/ArrayList 로 감싼다.
# 출처: Chaquopy 공식 conversion.pxi line 186~249 (p2j 에 dict 분기 부재).
from java import jclass

_HashMap = jclass("java.util.HashMap")
_ArrayList = jclass("java.util.ArrayList")


def _to_java(value):
    """Python dict/list 를 재귀적으로 java.util.HashMap/ArrayList 로 변환."""
    if isinstance(value, dict):
        java_map = _HashMap()
        for k, v in value.items():
            java_map.put(k, _to_java(v))
        return java_map
    if isinstance(value, list):
        java_list = _ArrayList()
        for item in value:
            java_list.add(_to_java(item))
        return java_list
    return value


# 보안상 import 를 차단할 최상위 모듈 이름.
# 본 앱은 교육용 파이썬 실행이므로 파일 시스템 / 네트워크 / 프로세스 제어 불필요.
FORBIDDEN_MODULES = {
    "os",
    "sys",
    "subprocess",
    "shutil",
    "socket",
    "http",
    "pathlib",
    "ctypes",
    "multiprocessing",
    "threading",
}


# 학생용 학습 기능에 필요한 builtins 화이트리스트.
# 파일/네트워크/동적 로딩(open, __import__, eval, exec 등)은 여기서 걸러낸다.
# AST 기반 FORBIDDEN_MODULES 필터가 1차 방어, 아래 화이트리스트가 2차 방어다.
_ALLOWED_BUILTINS_NAMES = {
    "print", "range", "len", "str", "int", "float", "bool",
    "list", "dict", "tuple", "set", "abs", "min", "max", "sum",
    "sorted", "reversed", "enumerate", "zip", "map", "filter",
    "round", "divmod", "pow", "isinstance",
    "True", "False", "None", "__build_class__", "__name__",
    # 클래스 상속 수업에서 `super().__init__()` 호출이 필요해 추가.
    # super() 내부는 CPython 이 자동 주입하는 __class__ cell 에 의존하지만,
    # 학생 코드에서 `__class__` 를 **명시적 속성 접근(ast.Attribute)** 으로
    # 쓰지 않는 한 _check_forbidden_attributes 에 걸리지 않는다
    # (현재 필터는 ast.Attribute 만 검사, ast.Name 참조는 허용).
    "super",
}
# `type` 은 3-arg 형태로 동적 클래스 생성(샌드박스 우회 악용) 가능 → 제거.
# 교육용 기본 학습에 필요한 타입 검사는 `isinstance` 로 대체 가능.


# 런타임에 dunder 속성을 거쳐 빌트인 / 서브클래스 체인에 접근하는 경로 차단.
# 예: ().__class__.__bases__[0].__subclasses__() → object subclass leak
#     (lambda: 0).__globals__ → 전역 네임스페이스 탈출
# validate_syntax() 이후 AST 에서 탐지되면 ForbiddenAttributeError 로 거부.
_FORBIDDEN_DUNDER_ATTRS = {
    "__subclasses__", "__bases__", "__mro__", "__globals__",
    "__code__", "__closure__", "__dict__", "__class__",
    "__builtins__", "__import__",
}


class ForbiddenAttributeError(Exception):
    """샌드박스 우회에 사용될 수 있는 dunder 속성 접근을 차단할 때 발생."""
    pass


def _build_safe_builtins() -> dict:
    """exec 에 주입할 제한된 builtins 딕셔너리를 구성한다.

    - __builtins__ 는 모듈이거나 dict 형태 둘 다 가능 (컨텍스트에 따라 다름).
    - 화이트리스트에 있고 실제로 존재하는 이름만 복사한다.
    """
    builtins_module = getattr(__builtins__, "__dict__", __builtins__)
    return {
        name: builtins_module[name]
        for name in _ALLOWED_BUILTINS_NAMES
        if name in builtins_module
    }


def _validate_syntax_raw(code_str: str) -> dict:
    """내부용: Python dict 그대로 반환 (execute_code 내부 참조용)."""
    try:
        ast.parse(code_str)
        return {"valid": True, "errors": []}
    except SyntaxError as e:
        return {
            "valid": False,
            "errors": [{
                "line": e.lineno or 1,
                "column": e.offset or 0,
                "message": str(e.msg),
            }],
        }


def validate_syntax(code_str: str) -> dict:
    """ast.parse() 로 파이썬 코드 문법 검증 (Kotlin 호출용 — Java Map 반환)."""
    return _to_java(_validate_syntax_raw(code_str))


def _check_forbidden_imports(code_str: str):
    """AST 를 돌며 import 문에 금지 모듈이 있는지 확인.

    발견 시 첫 번째 모듈 이름을 문자열로 반환, 없으면 None.
    """
    try:
        tree = ast.parse(code_str)
    except SyntaxError:
        # 문법 오류는 validate_syntax 에서 이미 처리됐다고 가정.
        return None

    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                top = alias.name.split(".", 1)[0]
                if top in FORBIDDEN_MODULES:
                    return top
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            top = module.split(".", 1)[0]
            if top in FORBIDDEN_MODULES:
                return top
    return None


def _check_forbidden_attributes(code_str: str):
    """AST 를 돌며 위험한 dunder 속성 접근을 탐지.

    발견 시 첫 번째 속성명을 반환, 없으면 None.
    `x.__class__` / `obj.__subclasses__()` 같은 AST `Attribute` 노드에서만 잡는다.
    (문자열 리터럴 내 `"__class__"` 같은 표현은 차단 대상이 아님.)
    """
    try:
        tree = ast.parse(code_str)
    except SyntaxError:
        return None

    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute) and node.attr in _FORBIDDEN_DUNDER_ATTRS:
            return node.attr
    return None


def execute_code(code_str: str) -> dict:
    """검증 통과한 코드를 실행하고 stdout / stderr 를 따로 캡처.

    반환 dict 키:
      - success: bool
      - output:  stdout 캡처 결과 (str)
      - stderr:  stderr 캡처 결과 (str)  ※ 7주차 신설
      - error:   예외/거부 메시지 (str | None)
      - error_type: "SyntaxError" / "ForbiddenModule" / 파이썬 예외 클래스명 / None
      - execution_time_ms: int
    """
    validation = _validate_syntax_raw(code_str)
    start_ms = int(time.time() * 1000)

    if not validation["valid"]:
        return _to_java({
            "success": False,
            "output": "",
            "stderr": "",
            "error": f"SyntaxError: {validation['errors'][0]['message']}",
            "error_type": "SyntaxError",
            "execution_time_ms": 0,
        })

    forbidden = _check_forbidden_imports(code_str)
    if forbidden is not None:
        return _to_java({
            "success": False,
            "output": "",
            "stderr": "",
            "error": f"금지된 모듈입니다: {forbidden}",
            "error_type": "ForbiddenModule",
            "execution_time_ms": 0,
        })

    forbidden_attr = _check_forbidden_attributes(code_str)
    if forbidden_attr is not None:
        return _to_java({
            "success": False,
            "output": "",
            "stderr": "",
            "error": f"금지된 속성 접근입니다: {forbidden_attr}",
            "error_type": "SecurityError",
            "execution_time_ms": 0,
        })

    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()

    try:
        with contextlib.redirect_stdout(stdout_buf), \
             contextlib.redirect_stderr(stderr_buf):
            compiled = compile(code_str, "<handcode>", "exec")
            # 화이트리스트된 builtins 만 노출 — 파일/네트워크/동적 실행 경로 전부 차단.
            exec(compiled, {"__builtins__": _build_safe_builtins()})
    except Exception as exc:
        elapsed = int(time.time() * 1000) - start_ms
        return _to_java({
            "success": False,
            "output": stdout_buf.getvalue(),
            "stderr": stderr_buf.getvalue(),
            "error": traceback.format_exc(limit=3),
            "error_type": type(exc).__name__,
            "execution_time_ms": elapsed,
        })

    elapsed = int(time.time() * 1000) - start_ms
    return _to_java({
        "success": True,
        "output": stdout_buf.getvalue(),
        "stderr": stderr_buf.getvalue(),
        "error": None,
        "error_type": None,
        "execution_time_ms": elapsed,
    })
