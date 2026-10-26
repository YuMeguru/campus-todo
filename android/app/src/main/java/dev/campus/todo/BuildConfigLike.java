package dev.campus.todo;

/** 版本号常量。不依赖 AGP 生成的 BuildConfig，方便单测与纯逻辑共用。 */
public final class BuildConfigLike {

    public static final String VERSION = "0.1.0-demo";

    private BuildConfigLike() {
    }
}
