// 顶层构建文件：统一声明 Android 与 Kotlin 插件版本，各模块按需应用
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
