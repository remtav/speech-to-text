# libct2whisper.so: CTranslate2 (static) + the Whisper bridge + JNI, in one library (issue #84).
# Configured by ../compiler.sh; CT2_SRC is the CTranslate2 source tree, built here as a static sub-project
# (its static install exports no CMake targets, and Ruy's libraries must be linked too).
cmake_minimum_required(VERSION 3.18)
project(ct2whisper CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID)
  # The NDK's static OpenMP runtime: without this, FindOpenMP picks libomp.so, which no phone has.
  file(GLOB CT2W_LIBOMP "${ANDROID_TOOLCHAIN_ROOT}/lib/clang/*/lib/linux/${CMAKE_SYSTEM_PROCESSOR}/libomp.a")
  if(NOT CT2W_LIBOMP)
    message(FATAL_ERROR "libomp.a not found in the NDK")
  endif()
  set(OpenMP_omp_LIBRARY "${CT2W_LIBOMP}" CACHE FILEPATH "" FORCE)
endif()
find_package(OpenMP REQUIRED)

# CPU only: Ruy for int8 and float32 GEMM, NEON kernels dispatched at run time, the compiler's OpenMP.
foreach(off BUILD_SHARED_LIBS BUILD_CLI BUILD_TESTS WITH_MKL WITH_DNNL WITH_OPENBLAS WITH_CUDA)
  set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(ENABLE_CPU_DISPATCH ON CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME COMP CACHE STRING "" FORCE)
# Cache entry: CTranslate2 unsets the normal variable after adding Ruy, which would leave it non-PIC.
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)
add_subdirectory("${CT2_SRC}" ct2 EXCLUDE_FROM_ALL)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "(arm64)|(aarch64)")
  set(CT2_ARCH_DEF CT2_ARM64_BUILD)
else()
  set(CT2_ARCH_DEF CT2_X86_BUILD)
endif()

add_library(ct2whisper_core STATIC ct2_whisper.cc)
target_include_directories(ct2whisper_core PRIVATE "${CT2_SRC}/src")
target_compile_definitions(ct2whisper_core PRIVATE ${CT2_ARCH_DEF})
target_link_libraries(ct2whisper_core PUBLIC ctranslate2 OpenMP::OpenMP_CXX)
set_target_properties(ct2whisper_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(ct2whisper SHARED ct2_whisper_jni.cc)
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(ct2whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_link_libraries(ct2whisper PRIVATE ct2whisper_core)
if(ANDROID)
  target_link_libraries(ct2whisper PRIVATE log)
  # Only the JNI entry points are exported; libc++ and libomp are linked in statically.
  target_link_options(ct2whisper PRIVATE -static-openmp -Wl,--exclude-libs,ALL -Wl,--gc-sections
                      -Wl,-z,max-page-size=16384)
endif()

add_executable(ct2_whisper_cli ct2_whisper_cli.cc)
target_link_libraries(ct2_whisper_cli PRIVATE ct2whisper_core)

install(TARGETS ct2whisper LIBRARY DESTINATION lib)
install(TARGETS ct2_whisper_cli RUNTIME DESTINATION bin)
