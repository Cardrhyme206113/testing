#!/usr/bin/env python3
import pathlib
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: apply_interpolation_aggression.py <GpuFrameGenerator.java>")

path = pathlib.Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
marker = "    private static final String INTERPOLATE_SHADER ="
start = source.find(marker)
if start < 0:
    raise SystemExit("INTERPOLATE_SHADER marker not found")

replacement = r'''    private static final String INTERPOLATE_SHADER =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "uniform sampler2D uPrev;\n"
                    + "uniform sampler2D uCurr;\n"
                    + "uniform sampler2D uForward;\n"
                    + "uniform sampler2D uBackward;\n"
                    + "uniform vec2 uFrameSize;\n"
                    + "uniform float uT;\n"
                    + "uniform int uHasFlow;\n"
                    + "in vec2 vUv;\n"
                    + "out vec4 outColor;\n"
                    + "float inside(vec2 uv) {\n"
                    + "  vec2 low = step(vec2(0.0), uv);\n"
                    + "  vec2 high = step(uv, vec2(1.0));\n"
                    + "  return low.x * low.y * high.x * high.y;\n"
                    + "}\n"
                    + "void main() {\n"
                    + "  if (uHasFlow == 0) { outColor = texture(uCurr, vUv); return; }\n"
                    + "  float aggression = clamp(float(uHasFlow - 1) / 100.0, 0.0, 1.0);\n"
                    + "  vec4 forwardData = texture(uForward, vUv);\n"
                    + "  vec4 backwardData = texture(uBackward, vUv);\n"
                    + "  vec2 forwardUv = forwardData.xy / uFrameSize;\n"
                    + "  vec2 backwardUv = backwardData.xy / uFrameSize;\n"
                    + "  vec2 previousMatch = vUv - forwardUv;\n"
                    + "  vec2 currentMatch = vUv - backwardUv;\n"
                    + "  vec4 backwardAtPrevious = texture(uBackward, clamp(previousMatch, vec2(0.0), vec2(1.0)));\n"
                    + "  vec4 forwardAtCurrent = texture(uForward, clamp(currentMatch, vec2(0.0), vec2(1.0)));\n"
                    + "  float forwardError = length(forwardData.xy + backwardAtPrevious.xy);\n"
                    + "  float backwardError = length(backwardData.xy + forwardAtCurrent.xy);\n"
                    + "  float consistencyEnd = mix(48.0, 160.0, aggression);\n"
                    + "  float rawForwardConsistency = (1.0 - smoothstep(5.0, consistencyEnd, forwardError)) * inside(previousMatch);\n"
                    + "  float rawBackwardConsistency = (1.0 - smoothstep(5.0, consistencyEnd, backwardError)) * inside(currentMatch);\n"
                    + "  float rawForwardReliability = clamp(forwardData.z * rawForwardConsistency, 0.0, 1.0);\n"
                    + "  float rawBackwardReliability = clamp(backwardData.z * rawBackwardConsistency, 0.0, 1.0);\n"
                    + "  float forceAmount = aggression * 0.82;\n"
                    + "  float forwardReliability = mix(rawForwardReliability, 1.0, forceAmount);\n"
                    + "  float backwardReliability = mix(rawBackwardReliability, 1.0, forceAmount);\n"
                    + "  vec2 prevUv = clamp(vUv - uT * forwardUv, vec2(0.0), vec2(1.0));\n"
                    + "  vec2 currUv = clamp(vUv - (1.0 - uT) * backwardUv, vec2(0.0), vec2(1.0));\n"
                    + "  vec4 warpedPrev = texture(uPrev, prevUv);\n"
                    + "  vec4 warpedCurr = texture(uCurr, currUv);\n"
                    + "  float baseWeight = mix(0.08, 0.58, aggression);\n"
                    + "  float prevWeight = (1.0 - uT) * (baseWeight + (1.0 - baseWeight) * forwardReliability);\n"
                    + "  float currWeight = uT * (baseWeight + (1.0 - baseWeight) * backwardReliability);\n"
                    + "  float rejection = mix(0.12, 1.0, aggression);\n"
                    + "  if (rawForwardReliability < 0.12 && rawBackwardReliability > 0.32) prevWeight *= rejection;\n"
                    + "  if (rawBackwardReliability < 0.12 && rawForwardReliability > 0.32) currWeight *= rejection;\n"
                    + "  vec4 generated = (warpedPrev * prevWeight + warpedCurr * currWeight)\n"
                    + "      / max(prevWeight + currWeight, 0.0001);\n"
                    + "  vec4 nearestOriginal = (uT < 0.5) ? texture(uPrev, vUv) : texture(uCurr, vUv);\n"
                    + "  float mismatch = max(max(abs(warpedPrev.r - warpedCurr.r), abs(warpedPrev.g - warpedCurr.g)), abs(warpedPrev.b - warpedCurr.b));\n"
                    + "  float photometric = 1.0 - smoothstep(0.20, mix(0.70, 1.35, aggression), mismatch);\n"
                    + "  float reliability = max(forwardReliability, backwardReliability);\n"
                    + "  float baseTrust = mix(0.76, 0.98, aggression);\n"
                    + "  float photoFloor = mix(0.90, 0.99, aggression);\n"
                    + "  float trust = (baseTrust + (1.0 - baseTrust) * reliability)\n"
                    + "      * (photoFloor + (1.0 - photoFloor) * photometric);\n"
                    + "  trust = mix(trust, 1.0, aggression * 0.82);\n"
                    + "  float fallbackThreshold = mix(0.025, 0.0001, aggression);\n"
                    + "  if (prevWeight + currWeight < fallbackThreshold) trust = 0.0;\n"
                    + "  outColor = mix(nearestOriginal, generated, clamp(trust, 0.0, 1.0));\n"
                    + "}\n";
}
'''

path.write_text(source[:start] + replacement, encoding="utf-8")
