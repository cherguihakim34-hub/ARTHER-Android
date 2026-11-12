"""A tiny deterministic stress test used to validate the experiment workflow."""
import random
random.seed(2026)
N = 100_000
inside = 0
for _ in range(N):
    x, y = random.random(), random.random()
    inside += (x*x + y*y <= 1)
estimate = 4 * inside / N
print({"trials": N, "pi_estimate": estimate, "absolute_error": abs(estimate - 3.141592653589793)})
assert 3.0 < estimate < 3.3
