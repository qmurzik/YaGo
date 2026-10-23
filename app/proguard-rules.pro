# Точка входа Xposed (assets/xposed_init) и код, работающий внутри чужого процесса.
-keep class com.qmurzik.yago.hook.** { *; }
-keep class com.qmurzik.yago.Config { *; }
-keep class com.qmurzik.yago.RuleEntry { *; }
# Метод подменяется хуком — нельзя ни удалять, ни инлайнить.
-keep class com.qmurzik.yago.ModuleStatus { *; }
-dontwarn de.robv.android.xposed.**
