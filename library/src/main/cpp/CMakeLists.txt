cmake_minimum_required(VERSION 3.22.1)

# Name your native module library
project("resize")

# Compile the source code into a shared native library
add_library(
        resize
        SHARED
        resize.cpp
)

# Locate Android's native graphics and logging libraries
find_library(jnigraphics-lib jnigraphics)
find_library(log-lib log)

# Link the graphics library (required for AndroidBitmap_* functions) and logging
target_link_libraries(
        resize
        ${jnigraphics-lib}
        ${log-lib}
)