cmake_minimum_required(VERSION 3.22.1)
project(blockhost_jvm C)

add_library(blockhost_jvm SHARED blockhost_jvm.c)
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(blockhost_jvm ${log-lib} ${dl-lib})
