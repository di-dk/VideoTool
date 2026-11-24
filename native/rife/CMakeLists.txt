# Builds the rife-ncnn-vulkan CLI (upstream: github.com/nihui/rife-ncnn-vulkan, MIT)
# against the prebuilt ncnn Android Vulkan package. Used only by native/scripts/build-rife.sh
cmake_minimum_required(VERSION 3.18)
project(rife-ncnn-vulkan C CXX)
set(CMAKE_CXX_STANDARD 14)
find_package(Threads)
find_package(OpenMP)
find_package(Vulkan REQUIRED)
find_package(ncnn REQUIRED)

set(SHADERS rife_preproc rife_postproc rife_preproc_tta rife_postproc_tta rife_flow_tta_avg
  rife_v2_flow_tta_avg rife_v4_flow_tta_avg rife_flow_tta_temporal_avg rife_v2_flow_tta_temporal_avg
  rife_v4_flow_tta_temporal_avg rife_out_tta_temporal_avg rife_v4_timestep rife_v4_timestep_tta
  warp warp_pack4 warp_pack8)
set(HEADERS "")
foreach(s ${SHADERS})
  set(h ${CMAKE_CURRENT_BINARY_DIR}/${s}.comp.hex.h)
  add_custom_command(OUTPUT ${h}
    COMMAND ${CMAKE_COMMAND} -DSHADER_SRC=${CMAKE_CURRENT_SOURCE_DIR}/${s}.comp -DSHADER_COMP_HEADER=${h}
            -P ${CMAKE_CURRENT_SOURCE_DIR}/generate_shader_comp_header.cmake
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${s}.comp VERBATIM)
  list(APPEND HEADERS ${h})
endforeach()
add_custom_target(gen_spirv DEPENDS ${HEADERS})
include_directories(${CMAKE_CURRENT_BINARY_DIR})

add_executable(librife_exec main.cpp rife.cpp warp.cpp hash_memory_shim.cpp)
add_dependencies(librife_exec gen_spirv)
set_target_properties(librife_exec PROPERTIES OUTPUT_NAME rife)
if(OpenMP_CXX_FOUND)
  target_link_libraries(librife_exec OpenMP::OpenMP_CXX)
endif()
target_link_libraries(librife_exec ncnn Vulkan::Vulkan)
target_link_options(librife_exec PRIVATE -Wl,-z,max-page-size=16384)
