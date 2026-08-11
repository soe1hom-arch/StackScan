# Copyright (C) 2026 soe1hom-arch (https://github.com/soe1hom-arch)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
cmake_minimum_required(VERSION 3.22.1)
project(stackscanraw C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_LCMS OFF CACHE BOOL "" FORCE)
set(ENABLE_JASPER OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENMP OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_RAWSPEED OFF CACHE BOOL "" FORCE)
set(ENABLE_X3FTOOLS OFF CACHE BOOL "" FORCE)
set(ENABLE_6BY9RPI OFF CACHE BOOL "" FORCE)
set(LIBRAW_INSTALL OFF CACHE BOOL "" FORCE)
set(LIBRAW_UNINSTALL_TARGET OFF CACHE BOOL "" FORCE)

add_subdirectory(libraw libraw-build)

add_library(rawdecoder SHARED rawdecoder.cpp)
target_include_directories(rawdecoder PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(rawdecoder PRIVATE raw)
