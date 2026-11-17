cmake_minimum_required(VERSION 3.22.1)
project(abotalal_php_jni LANGUAGES C)

add_library(abotalal_php_jni SHARED
    abotalal_php_jni.c
)

target_include_directories(abotalal_php_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../build-scripts/php-android
)

target_link_options(abotalal_php_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(abotalal_php_jni
    ${log-lib}
    dl
)
