cmake_minimum_required(VERSION 3.22.1)
set(PRIVATE_4J_WORLD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/staged")
foreach(mod IN ITEMS Random ImprovedNoise PerlinNoise Mth Synth)
  if(NOT EXISTS "${PRIVATE_4J_WORLD_DIR}/${mod}.cpp")
    message(FATAL_ERROR "Original 4J module missing: ${mod}. Run stage-original-4j.py first")
  endif()
endforeach()
add_library(original4jworld_core STATIC
  "${PRIVATE_4J_WORLD_DIR}/Random.cpp"
  "${PRIVATE_4J_WORLD_DIR}/ImprovedNoise.cpp"
  "${PRIVATE_4J_WORLD_DIR}/PerlinNoise.cpp"
  "${PRIVATE_4J_WORLD_DIR}/Mth.cpp"
  "${PRIVATE_4J_WORLD_DIR}/Synth.cpp"
)
target_compile_features(original4jworld_core PUBLIC cxx_std_17)
target_include_directories(original4jworld_core PUBLIC "${PRIVATE_4J_WORLD_DIR}")
target_compile_options(original4jworld_core PRIVATE -Wall -Wextra -Wno-delete-non-virtual-dtor)
