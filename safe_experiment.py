"""A bounded arithmetic simulation DSL. It never executes Python bytecode or imports user code."""
import ast
import json
import math

MAX_SOURCE_CHARS = 5000
MAX_AST_NODES = 600
MAX_STEPS = 20000
MAX_RANGE_ITEMS = 5000
MAX_OUTPUT_CHARS = 12000
MAX_LIST_ITEMS = 2000

_FUNCTIONS = {
    "abs": abs, "min": min, "max": max, "round": round,
    "sum": sum, "len": len, "int": int, "float": float,
    "str": str, "bool": bool,
    "sqrt": math.sqrt, "sin": math.sin, "cos": math.cos,
    "tan": math.tan, "asin": math.asin, "acos": math.acos,
    "atan": math.atan, "atan2": math.atan2, "exp": math.exp,
    "log": math.log, "log10": math.log10, "floor": math.floor,
    "ceil": math.ceil, "fabs": math.fabs, "hypot": math.hypot,
    "degrees": math.degrees, "radians": math.radians,
}
_CONSTANTS = {"pi": math.pi, "e": math.e, "tau": math.tau}


class ExperimentError(ValueError):
    pass


class SafeExperiment:
    def __init__(self, source):
        if not isinstance(source, str) or not source.strip():
            raise ExperimentError("أدخل معادلات أو خطوات المحاكاة أولاً.")
        if len(source) > MAX_SOURCE_CHARS:
            raise ExperimentError("حجم التجربة أكبر من الحد المسموح (5000 حرف).")
        try:
            self.tree = ast.parse(source, mode="exec")
        except Exception as exc:
            line = getattr(exc, "lineno", None) or "?"
            message = getattr(exc, "msg", str(exc))
            raise ExperimentError(f"خطأ في صياغة التجربة عند السطر {line}: {message}") from exc
        if sum(1 for _ in ast.walk(self.tree)) > MAX_AST_NODES:
            raise ExperimentError("التجربة معقدة أكثر من الحد المسموح.")
        self.env = dict(_FUNCTIONS)
        self.env.update(_CONSTANTS)
        self.env["results"] = []
        self.output = []
        self.steps = 0
        self.depth = 0

    def tick(self):
        self.steps += 1
        if self.steps > MAX_STEPS:
            raise ExperimentError("تجاوزت التجربة حد العمليات الآمن (20000 خطوة).")

    def run(self):
        try:
            self.exec_block(self.tree.body)
        except ExperimentError:
            raise
        except Exception as exc:
            raise ExperimentError(f"تعذر تنفيذ العملية بأمان: {str(exc)[:180]}") from exc
        variables = {}
        for key, value in self.env.items():
            if key.startswith("_") or key in _FUNCTIONS or key in _CONSTANTS or key == "results":
                continue
            variables[key] = self.clean(value)
        return {
            "status": "completed",
            "engine": "ARTHER Safe Experiment DSL",
            "steps": self.steps,
            "stdout": "\n".join(self.output),
            "variables": variables,
            "results": self.clean(self.env["results"]),
        }

    def clean(self, value):
        if isinstance(value, bool) or value is None:
            return value
        if isinstance(value, int):
            if abs(value) > 10 ** 100:
                raise ExperimentError("القيمة الصحيحة تجاوزت المجال العددي الآمن.")
            return value
        if isinstance(value, str):
            if len(value) > 2000:
                return value[:2000] + "…"
            return value
        if isinstance(value, float):
            if not math.isfinite(value):
                raise ExperimentError("نتجت قيمة غير محدودة؛ راجع المعادلات.")
            return value
        if isinstance(value, (list, tuple)):
            if len(value) > MAX_LIST_ITEMS:
                raise ExperimentError("قائمة النتائج تجاوزت 2000 عنصر.")
            return [self.clean(item) for item in value]
        raise ExperimentError("نوع بيانات غير مسموح في النتائج.")

    def exec_block(self, statements):
        for statement in statements:
            self.tick()
            self.exec_stmt(statement)

    def exec_stmt(self, node):
        if isinstance(node, ast.Assign):
            if len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
                raise ExperimentError("يسمح بالإسناد إلى متغير واحد فقط في كل سطر.")
            name = node.targets[0].id
            if name.startswith("_") or name in _FUNCTIONS or name in _CONSTANTS or name in {"range", "print", "emit", "results"}:
                raise ExperimentError("اسم المتغير محجوز أو غير مسموح.")
            self.env[name] = self.eval_expr(node.value)
            return
        if isinstance(node, ast.AugAssign) and isinstance(node.target, ast.Name):
            name = node.target.id
            if name not in self.env:
                raise ExperimentError(f"المتغير {name} غير معرّف.")
            self.env[name] = self.apply_binop(node.op, self.env[name], self.eval_expr(node.value))
            return
        if isinstance(node, ast.Expr):
            self.eval_expr(node.value)
            return
        if isinstance(node, ast.For):
            if not isinstance(node.target, ast.Name) or not isinstance(node.iter, ast.Call) or not isinstance(node.iter.func, ast.Name) or node.iter.func.id != "range":
                raise ExperimentError("حلقة for يجب أن تستخدم range(...) فقط.")
            if node.target.id.startswith("_") or node.target.id in _FUNCTIONS or node.target.id in _CONSTANTS or node.target.id in {"range", "print", "emit", "results"}:
                raise ExperimentError("اسم متغير الحلقة محجوز أو غير مسموح.")
            values = self.eval_expr(node.iter)
            for value in values:
                self.tick()
                self.env[node.target.id] = value
                self.exec_block(node.body)
            if node.orelse:
                self.exec_block(node.orelse)
            return
        if isinstance(node, ast.If):
            branch = node.body if self.truth(self.eval_expr(node.test)) else node.orelse
            self.exec_block(branch)
            return
        if isinstance(node, ast.Pass):
            return
        raise ExperimentError(f"تعليمة غير مسموحة: {type(node).__name__}.")

    def truth(self, value):
        return bool(value)

    def eval_expr(self, node):
        self.tick()
        if isinstance(node, ast.Constant):
            if isinstance(node.value, (str, int, float, bool)) or node.value is None:
                return node.value
            raise ExperimentError("قيمة ثابتة غير مسموحة.")
        if isinstance(node, ast.Name):
            if node.id not in self.env:
                raise ExperimentError(f"المتغير {node.id} غير معرّف.")
            return self.env[node.id]
        if isinstance(node, (ast.List, ast.Tuple)):
            values = [self.eval_expr(item) for item in node.elts]
            if len(values) > MAX_LIST_ITEMS:
                raise ExperimentError("القائمة أكبر من الحد المسموح.")
            return values
        if isinstance(node, ast.BinOp):
            return self.apply_binop(node.op, self.eval_expr(node.left), self.eval_expr(node.right))
        if isinstance(node, ast.UnaryOp):
            value = self.eval_expr(node.operand)
            if isinstance(node.op, ast.UAdd): return +value
            if isinstance(node.op, ast.USub): return -value
            if isinstance(node.op, ast.Not): return not value
            raise ExperimentError("عملية أحادية غير مسموحة.")
        if isinstance(node, ast.BoolOp):
            values = [self.truth(self.eval_expr(item)) for item in node.values]
            return all(values) if isinstance(node.op, ast.And) else any(values)
        if isinstance(node, ast.Compare):
            left = self.eval_expr(node.left)
            for op, comparator in zip(node.ops, node.comparators):
                right = self.eval_expr(comparator)
                ok = (
                    left == right if isinstance(op, ast.Eq) else
                    left != right if isinstance(op, ast.NotEq) else
                    left < right if isinstance(op, ast.Lt) else
                    left <= right if isinstance(op, ast.LtE) else
                    left > right if isinstance(op, ast.Gt) else
                    left >= right if isinstance(op, ast.GtE) else None
                )
                if ok is None: raise ExperimentError("نوع مقارنة غير مسموح.")
                if not ok: return False
                left = right
            return True
        if isinstance(node, ast.IfExp):
            return self.eval_expr(node.body if self.truth(self.eval_expr(node.test)) else node.orelse)
        if isinstance(node, ast.Subscript):
            container = self.eval_expr(node.value)
            index = self.eval_expr(node.slice)
            if not isinstance(container, (list, tuple, str)) or not isinstance(index, int):
                raise ExperimentError("الفهرسة مسموحة للقوائم والنصوص باستخدام عدد صحيح فقط.")
            return container[index]
        if isinstance(node, ast.Call):
            if isinstance(node.func, ast.Attribute):
                if node.func.attr != "append" or not isinstance(node.func.value, ast.Name) or node.func.value.id != "results" or len(node.args) != 1 or node.keywords:
                    raise ExperimentError("يسمح فقط باستعمال results.append(value).")
                values = self.env["results"]
                if len(values) >= MAX_LIST_ITEMS:
                    raise ExperimentError("تجاوزت قائمة النتائج الحد المسموح.")
                values.append(self.eval_expr(node.args[0]))
                return None
            if not isinstance(node.func, ast.Name) or node.func.id not in _FUNCTIONS and node.func.id not in {"range", "print", "emit"}:
                raise ExperimentError("الدالة المطلوبة غير مسموحة.")
            if node.keywords:
                raise ExperimentError("المعاملات المسماة غير مدعومة.")
            args = [self.eval_expr(arg) for arg in node.args]
            name = node.func.id
            if name == "range":
                if not 1 <= len(args) <= 3 or any(not isinstance(v, int) for v in args):
                    raise ExperimentError("range يقبل من 1 إلى 3 أعداد صحيحة.")
                values = range(*args)
                try:
                    count = len(values)
                except OverflowError as exc:
                    raise ExperimentError("مدى الحلقة أكبر من الحد المسموح.") from exc
                if count > MAX_RANGE_ITEMS:
                    raise ExperimentError("الحد الأقصى لحلقة range هو 5000 تكرار.")
                return values
            if name in {"print", "emit"}:
                line = " ".join(str(value) for value in args)
                if sum(len(item) for item in self.output) + len(line) > MAX_OUTPUT_CHARS:
                    raise ExperimentError("تجاوزت مخرجات التجربة 12000 حرف.")
                self.output.append(line)
                return None
            try:
                result = _FUNCTIONS[name](*args)
            except Exception as exc:
                raise ExperimentError(f"فشلت الدالة {name}: {str(exc)[:180]}") from exc
            return result
        raise ExperimentError(f"عملية غير مسموحة: {type(node).__name__}.")

    def apply_binop(self, operator, left, right):
        if isinstance(operator, ast.Add) and isinstance(left, (str, list, tuple)) and isinstance(right, type(left)):
            if len(left) + len(right) > (2000 if isinstance(left, str) else MAX_LIST_ITEMS):
                raise ExperimentError("حجم الناتج تجاوز الحد المسموح.")
        if isinstance(operator, ast.Mult):
            if isinstance(left, (str, list, tuple)) and isinstance(right, int):
                limit = 2000 if isinstance(left, str) else MAX_LIST_ITEMS
                if len(left) * abs(right) > limit:
                    raise ExperimentError("حجم الناتج تجاوز الحد المسموح.")
            if isinstance(right, (str, list, tuple)) and isinstance(left, int):
                limit = 2000 if isinstance(right, str) else MAX_LIST_ITEMS
                if len(right) * abs(left) > limit:
                    raise ExperimentError("حجم الناتج تجاوز الحد المسموح.")
        try:
            if isinstance(operator, ast.Add): result = left + right
            elif isinstance(operator, ast.Sub): result = left - right
            elif isinstance(operator, ast.Mult): result = left * right
            elif isinstance(operator, ast.Div): result = left / right
            elif isinstance(operator, ast.FloorDiv): result = left // right
            elif isinstance(operator, ast.Mod): result = left % right
            elif isinstance(operator, ast.Pow):
                if not isinstance(right, (int, float)) or abs(right) > 12:
                    raise ExperimentError("الأس يجب ألا يتجاوز 12.")
                result = left ** right
            else:
                raise ExperimentError("عملية حسابية غير مسموحة.")
        except ExperimentError:
            raise
        except Exception as exc:
            raise ExperimentError(f"خطأ حسابي: {str(exc)[:180]}") from exc
        if isinstance(result, (int, float)) and (abs(result) > 1e100 or isinstance(result, float) and not math.isfinite(result)):
            raise ExperimentError("القيمة الناتجة تجاوزت المجال العددي الآمن.")
        if isinstance(result, (str, list, tuple)) and len(result) > MAX_LIST_ITEMS:
            raise ExperimentError("حجم الناتج تجاوز الحد المسموح.")
        return result


def run_experiment(source):
    """Run a small, deterministic simulation without exec/eval or arbitrary imports."""
    return SafeExperiment(source).run()
