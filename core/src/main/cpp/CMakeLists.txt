cmake_minimum_required(VERSION 3.22.1)

project("fsnative" CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    fsnative
    SHARED
    src/FatFormatter.cpp
    src/ExFatFormatter.cpp
    src/Ext4Formatter.cpp
    src/NtfsFormatter.cpp
    src/jni_bridge.cpp
)

target_include_directories(
    fsnative
    PRIVATE
    include
)

find_library(log-lib log)

target_link_libraries(
    fsnative
    PRIVATE
    ${log-lib}
)

# Requisito estrito para Android 15 e 16+: Alinhamento de memória para páginas de 16 KB
target_link_options(
    fsnative
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
