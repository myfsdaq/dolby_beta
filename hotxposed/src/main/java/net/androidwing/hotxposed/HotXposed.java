package net.androidwing.hotxposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created  on 2018/3/30.
 * Fixed for High API crash support
 */
public class HotXposed {
    public static void hook(Class<?> clazz, XC_LoadPackage.LoadPackageParam lpparam)
            throws Exception {
        String packageName = clazz.getName().replace("." + clazz.getSimpleName(), "");
        try {
            File apkFile = getApkFile(packageName, lpparam);

            if (!apkFile.exists()) {
                Log.e("HotXposed", "apk file not found: " + apkFile.getAbsolutePath());
                return;
            }

            filterNotify(lpparam);

            PathClassLoader classLoader = new PathClassLoader(apkFile.getAbsolutePath(), lpparam.getClass().getClassLoader());
            Class<?> cls = classLoader.loadClass(clazz.getName());
            if (cls != null) {
                Method method = cls.getDeclaredMethod("dispatch", XC_LoadPackage.LoadPackageParam.class);
                method.setAccessible(true);
                // 增加 try-catch 保护，防止模块内部错误炸毁宿主应用
                try {
                    method.invoke(cls.newInstance(), lpparam);
                } catch (Throwable t) {
                    XposedBridge.log("DolbyBeta: HotXposed dispatch failed");
                    XposedBridge.log(t);
                }
            }
        } catch (Throwable e) {
            // 捕获获取 APK 文件等过程中的异常
            XposedBridge.log("DolbyBeta: HotXposed init failed");
            XposedBridge.log(e);
        }
    }

    private static void filterNotify(XC_LoadPackage.LoadPackageParam lpparam)
            throws ClassNotFoundException {
        if ("de.robv.android.xposed.installer".equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(lpparam.classLoader.loadClass("de.robv.android.xposed.installer.util.NotificationUtil"),
                    "showModulesUpdatedNotification", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(new Object());
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
                        }
                    });
        }
    }

    private static File getApkFile(String packageName, XC_LoadPackage.LoadPackageParam lpparam) throws PackageManager.NameNotFoundException {
        // 修复：在高版本 Android 上，使用 BootClassLoader (null) 查找 ActivityThread 更稳健
        // 同时拆分调用链以避免 NullPointerException
        Object currentActivityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");

        if (currentActivityThread == null) {
            throw new IllegalStateException("Failed to get currentActivityThread, likely too early in process start");
        }

        Context systemContext = (Context) XposedHelpers.callMethod(currentActivityThread, "getSystemContext");
        
        if (systemContext == null) {
            throw new IllegalStateException("Failed to get SystemContext");
        }

        ApplicationInfo applicationInfo = systemContext.getPackageManager().getApplicationInfo(packageName, 0);
        return new File(applicationInfo.sourceDir);
    }
}
