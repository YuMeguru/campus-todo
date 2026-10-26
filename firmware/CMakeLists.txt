cmake_minimum_required(VERSION 3.16)

# 只读引用 ai-passport 的 BSP：不复制、不修改那个仓库，也不碰它里面在飞的改动。
# 代价是这个工程不能单独 clone 构建，必须有兄弟目录 ai-passport（见 README）。
set(EXTRA_COMPONENT_DIRS
    "${CMAKE_CURRENT_LIST_DIR}/../../ai-passport/components")

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(campus-todo-firmware)
