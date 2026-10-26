# 字体由 tools/font/make_font.js 生成（LV_MEM 与字形清单都靠它）。
# 生成物提交在 main/fonts/ 下，构建本身不依赖 node。
set(CAMPUS_FONTS
    "fonts/campus_font_16.c"
    "fonts/campus_font_20.c"
)

idf_component_register(
    SRCS "campus_main.c"
         "campus_ble.c"
         "campus_store.c"
         "campus_ui.c"
         "campus_frame.c"
         "campus_snapshot.c"
         "campus_model.c"
         ${CAMPUS_FONTS}
    INCLUDE_DIRS "."
    REQUIRES bsp nvs_flash esp_timer bt
)
