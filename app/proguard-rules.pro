-keep class cn.arbore.shell.** { *; }
-keep class cn.jpush.** { *; }
-keep class cn.jiguang.** { *; }
-dontwarn cn.jpush.**
-dontwarn cn.jiguang.**

-keepattributes JavascriptInterface
-keepclassmembers class cn.arbore.shell.AndroidBridge {
    public *;
}
