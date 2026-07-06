cmake_minimum_required(VERSION 3.18)

# Copy _hcompress.c from desktop project or use bundled copy
if(EXISTS "${CMAKE_SOURCE_DIR}/_hcompress.c")
    set(HCOMPRESS_SRC "${CMAKE_SOURCE_DIR}/_hcompress.c")
else()
    # Bundled copy inside jni folder
    set(HCOMPRESS_SRC "${CMAKE_CURRENT_SOURCE_DIR}/_hcompress.c")
endif()

add_library(hcompress SHARED ${HCOMPRESS_SRC})
target_compile_options(hcompress PRIVATE -O3 -march=armv8-a)
target_link_libraries(hcompress android log)
