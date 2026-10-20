cmake_minimum_required(VERSION 3.22.1)
project(quest_execution_probe LANGUAGES C CXX)
add_library(questterminal SHARED quest_terminal.c)
target_compile_options(questterminal PRIVATE -Wall -Wextra -Werror)
target_link_options(questterminal PRIVATE "-Wl,-z,max-page-size=16384")
find_package(OpenXR REQUIRED CONFIG)
add_library(native_app_glue STATIC
    ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC
    ${ANDROID_NDK}/sources/android/native_app_glue)
set(QUEST_NATIVE_SDK ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-sdk)
add_library(questhost MODULE quest_host.c)
target_include_directories(questhost PRIVATE ${QUEST_NATIVE_SDK})
target_link_libraries(questhost PRIVATE android log dl)
target_link_options(questhost PRIVATE "-Wl,-z,max-page-size=16384")
add_library(questtelemetry MODULE quest_telemetry.cpp ${QUEST_NATIVE_SDK}/identity.c)
target_include_directories(questtelemetry PRIVATE ${QUEST_NATIVE_SDK})
target_compile_definitions(questtelemetry PRIVATE QUEST_NATIVE_APP_NAME="Quest telemetry")
target_compile_features(questtelemetry PRIVATE cxx_std_17)
target_compile_definitions(questtelemetry PRIVATE
    XR_USE_PLATFORM_ANDROID XR_USE_GRAPHICS_API_OPENGL_ES)
target_link_libraries(questtelemetry PRIVATE
    OpenXR::openxr_loader native_app_glue android EGL GLESv3 log)
set_target_properties(questtelemetry PROPERTIES LINK_FLAGS "-u ANativeActivity_onCreate")
