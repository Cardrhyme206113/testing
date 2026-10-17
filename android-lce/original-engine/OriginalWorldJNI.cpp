#include <jni.h>
#include <string>
#if defined(LCE_HAS_ORIGINAL_4J_WORLD)
#include "Random.h"
#include "PerlinNoise.h"
#endif
// This bridge is informational, not the entire engine. The true Level and game
// loop are intentionally NOT replaced by an imitation implementation.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_card_lceandroid_MainActivity_nativeOriginalWorldProbe(JNIEnv* env,jclass) {
#if defined(LCE_HAS_ORIGINAL_4J_WORLD)
  Random r(987654321);
  PerlinNoise noise(&r,4);
  const double actual=noise.getValue(-2.25,19.5,5.75);
  const bool accurate=(actual > -2.3645223938745 && actual < -2.3645223938734);
  const std::string result=accurate
    ? "Original 4J RNG/Perlin: linked and verified (not full Level engine)"
    : "Original 4J RNG/Perlin: linked, REFERENCE MISMATCH";
#else
  const std::string result="Original 4J engine not linked (prototype renderer only)";
#endif
  return env->NewStringUTF(result.c_str());
}
