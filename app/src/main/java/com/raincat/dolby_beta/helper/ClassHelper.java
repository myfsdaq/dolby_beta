package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;


import com.annimon.stream.Stream;

import com.raincat.dolby_beta.utils.Tools;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.dexbacked.DexBackedClassDef;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.MultiDexContainer;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;
import static de.robv.android.xposed.XposedHelpers.findMethodsByExactParameters;

/**
 * <pre>
 * author : RainCat
 * e-mail : nining377@gmail.com
 * time   : 2021/04/14
 * desc   : 类加载帮助
 * version: 1.0
 * </pre>
 */

public class ClassHelper {
    //类加载器
    private static ClassLoader classLoader = null;
    //dex缓存
    private static List<String> classCacheList = null;
    //dex缓存路径
    private static String classCachePath = null;
    //网易云版本
    private static int versionCode = 0;
    // 保存 Context 以便后续全量加载使用
    private static Context mContext;
    // 标记是否已经全量加载过
    private static boolean isFullLoaded = false;

    public static synchronized void getCacheClassList(final Context context, final int version, final OnCacheClassListener listener) {
        mContext = context;
        if (classLoader == null) {
            classLoader = context.getClassLoader();
            versionCode = version;
            File cacheFile = Objects.requireNonNull(context.getExternalFilesDir(null));
            if (cacheFile.exists() || cacheFile.mkdirs())
                classCachePath = cacheFile.getPath();
        }
        if (classCacheList == null) {
            if (SettingHelper.getInstance().isEnable(SettingHelper.dex_key))
                classCacheList = FileHelper.readFileFromSD(classCachePath + File.separator + "class-" + version);
            else
                classCacheList = new ArrayList<>();
            if (classCacheList.size() == 0) {
                // 默认非全量加载
                new Thread(() -> getCacheClassByZip(context, version, listener, false)).start();
            } else
                listener.onGet();
        } else
            listener.onGet();
    }

    private static synchronized void getCacheClassByZip(Context context, int version, OnCacheClassListener listener, boolean loadAll) {
        try {
            // 不用 ZipDexContainer 因为会验证zip里面的文件是不是dex，会慢一点
            File appInstallFile = new File(context.getPackageResourcePath());
            Enumeration<? extends ZipEntry> zip = new ZipFile(appInstallFile).entries();
            while (zip.hasMoreElements()) {
                ZipEntry dexInZip = zip.nextElement();
                if (dexInZip.getName().startsWith("classes") && dexInZip.getName().endsWith(".dex")) {
                    MultiDexContainer.DexEntry<? extends DexBackedDexFile> dexEntry = DexFileFactory.loadDexEntry(appInstallFile, dexInZip.getName(), true, null);
                    DexBackedDexFile dexFile = dexEntry.getDexFile();
                    for (DexBackedClassDef classDef : dexFile.getClasses()) {
                        String classType = classDef.getType();
                        boolean shouldAdd = false;

                        if (loadAll) {
                            // 全量模式：排除系统类和常用框架类
                            if (!classType.startsWith("Landroid") &&
                                !classType.startsWith("Ljava") &&
                                !classType.startsWith("Ljavax") &&
                                !classType.startsWith("Lkotlin") &&
                                !classType.startsWith("Landroidx")) {
                                shouldAdd = true;
                            }
                        } else {
                            // 默认模式：包含特定包名
                            // 修改：增加正则匹配 ^L[^/]+/[^/]+;$ 用于匹配如 Lad4/a; 这样的一级目录包名
                            if (classType.contains("com/netease/cloudmusic") || 
                                classType.contains("okhttp3") ||
                                classType.matches("^L[^/]+/[^/]+;$")) {
                                shouldAdd = true;
                            }
                        }

                        if (shouldAdd) {
                            classType = classType.substring(1, classType.length() - 1).replace("/", ".");
                            classCacheList.add(classType);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (!loadAll) {
                FileHelper.writeFileFromSD(classCachePath + File.separator + "class-" + version, classCacheList);
            }
            if (listener != null) {
                listener.onGet();
            }
        }
    }

    public interface OnCacheClassListener {
        void onGet();
    }

    public static List<String> getFilteredClasses(Pattern pattern, Comparator<String> comparator) {
        List<String> list = Stream.of(classCacheList)
                .filter(s -> pattern.matcher(s).find())
                .toList();

        // 修改：如果检测到 list 为空且未全量加载，则进行全量加载
        if (list.isEmpty() && !isFullLoaded && mContext != null) {
            XposedBridge.log("DolbyBeta: No class found for pattern " + pattern.toString() + ", trying full scan...");
            synchronized (ClassHelper.class) {
                if (classCacheList != null) {
                    classCacheList.clear();
                } else {
                    classCacheList = new ArrayList<>();
                }
                // 同步调用全量加载
                getCacheClassByZip(mContext, versionCode, null, true);
                isFullLoaded = true;
            }
            // 重新过滤
            list = Stream.of(classCacheList)
                    .filter(s -> pattern.matcher(s).find())
                    .toList();
        }

        Collections.sort(list, comparator);
        return list;
    }

    private static Class<?> getClassByXposed(String className) {
        Class<?> clazz = findClassIfExists(className, classLoader);
        if (clazz == null)
            clazz = findClassIfExists("com.netease.cloudmusic.NeteaseMusicApplication", classLoader);
        return clazz;
    }

    // ... (后续的内部类 Cookie, DownloadTransfer 等保持不变，因为它们都调用了 getFilteredClasses)
    
    public static class Cookie {
        private static Class<?> clazz, abstractClazz;

        public static String getCookie(Context context) {
            if (clazz == null) {
                Pattern pattern;
                if (versionCode < 154)
                    pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z]\\.[a-z]\\.[a-z]\\.[a-z]$");
                else if (versionCode < 8008050)
                    pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.[a-z]\\.[a-z]\\.[a-z]$");
                else
                    pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.network\\.cookie\\.store\\.[a-zA-Z0-9]{1,25}$");


                List<String> list = getFilteredClasses(pattern, null);

                try {
                    abstractClazz = Stream.of(list)
                            .map(ClassHelper::getClassByXposed)
                            .filter(c -> Modifier.isPublic(c.getModifiers()))
                            .filter(c -> c.getSuperclass() == Object.class)
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == ConcurrentHashMap.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == SharedPreferences.class))
                            .filter(c -> Stream.of(c.getDeclaredFields()).anyMatch(m -> m.getType() == long.class))
                            .findFirst()
                            .get();

                  if (versionCode >= 154) {
                        clazz = Stream.of(list)
                                .map(ClassHelper::getClassByXposed)
                                .filter(c -> Modifier.isPublic(c.getModifiers()))
                                .filter(m -> !Modifier.isInterface(m.getModifiers()))
                                .filter(c -> c.getSuperclass() == abstractClazz)
                                .findFirst()
                                .get();
                    } else {
                        clazz = abstractClazz;
                    }
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.cookieClassNotFoundCode);
                }
            }

            Object cookieString = null;
          if (versionCode >= 154) {
                //获取静态cookie方法
                Method cookieMethod = XposedHelpers.findMethodsByExactParameters(clazz, clazz)[0];
                Object cookie = XposedHelpers.callStaticMethod(clazz, cookieMethod.getName());
              for (Method method : XposedHelpers.findMethodsByExactParameters(abstractClazz, String.class)) {
                    if (method.getTypeParameters().length == 0 && method.getModifiers() == Modifier.PUBLIC) {
                        cookieString = XposedHelpers.callMethod(cookie, method.getName());
                    }
                }
            } else {
                Method cookieMethod = XposedHelpers.findMethodsByExactParameters(clazz, String.class)[0];
                cookieString = XposedHelpers.callStaticMethod(clazz, cookieMethod.getName());
            }

            return "MUSIC_U=" + cookieString;
        }
    }

    public static class DownloadTransfer {
        private static Method checkMd5Method;
        private static Method checkDownloadStatusMethod;

        //下载完后的MD5检查
        public static Method getCheckMd5Method(Context context) {
            if (checkMd5Method == null) {
                Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.transfer\\.download\\.[a-z0-9]{1,2}$");
                List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                try {
                    checkMd5Method = Stream.of(list)
                            .map(c -> getClassByXposed(c).getDeclaredMethods())
                            .flatMap(Stream::of)
                            .filter(m -> m.getParameterTypes().length == 4)
                            .filter(m -> m.getParameterTypes()[0] == File.class)
                            .filter(m -> m.getParameterTypes()[1] == File.class)
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
                }
            }
            return checkMd5Method;
        }

        //下载之前下载状态检查
        public static Method getCheckDownloadStatusMethod(Context context) {
            if (checkDownloadStatusMethod == null) {
                Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.transfer\\.download\\.[a-z0-9]{1,2}$");
                List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());

                try {
                    checkDownloadStatusMethod = Stream.of(list)
                            .map(c -> getClassByXposed(c).getDeclaredMethods())
                            .flatMap(Stream::of)
                            .filter(m -> m.getReturnType() == long.class)
                            .filter(m -> m.getParameterTypes().length == 5)
                            .filter(m -> m.getParameterTypes()[1] == int.class)
                            .filter(m -> m.getParameterTypes()[3] == File.class)
                            .filter(m -> m.getParameterTypes()[4] == long.class)
                            .findFirst()
                            .get();
                } catch (NoSuchElementException e) {
                    MessageHelper.sendNotification(context, MessageHelper.transferClassNotFoundCode);
                }
            }
            return checkDownloadStatusMethod;
        }
    }

    public static class MainActivitySuperClass {
        private static Class<?> clazz;
        private static List<Method> methods;
        private static Method method;

        static void getClazz(Context context) {
            if (clazz == null) {
                Class<?> mainActivityClass = findClass("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader());
                clazz = mainActivityClass.getSuperclass();
            }
        }

        public static List<Method> getTabItemStringMethods(Context context) {
            if (clazz == null)
                getClazz(context);
            if (methods == null && clazz != null) {
                List<Method> methodList = Arrays.asList(clazz.getDeclaredMethods());
                methods = Stream.of(methodList)
                        .filter(m -> m.getParameterTypes().length >= 1)
                        .filter(m -> m.getReturnType() == void.class)
                        .filter(m -> m.getParameterTypes()[0] == String[].class)
                        .filter(m -> Modifier.isPublic(m.getModifiers()))
                        .toList();
            }
            return methods;
        }

        public static Method getViewPagerInitMethod(Context context) {
            if (method == null) {
                try {
                    List<Method> methodList = Arrays.asList(findClass("com.netease.cloudmusic.activity.MainActivity", context.getClassLoader()).getDeclaredMethods());
                    method = Stream.of(methodList)
                            .filter(m -> m.getParameterTypes().length == 1)
                            .filter(m -> m.getReturnType() == void.class)
                            .filter(m -> m.getParameterTypes()[0] == Intent.class)
                            .filter(m -> Modifier.isPrivate(m.getModifiers()))
                            .findFirst()
                            .get();
                } catch (Exception e) {
                    MessageHelper.sendNotification(context, MessageHelper.tabClassNotFoundCode);
                }
            }
            return method;
        }
    }

    public static class BottomTabView {
        private static Class<?> clazz;
        private static Method initMethod, refreshMethod;

        public static Class<?> getClazz(Context context) {
            if (clazz == null) {
                try {
                    Pattern pattern = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.[a-z0-9]{1,2}\\.[a-z]$");
                    Pattern pattern2 = Pattern.compile("^com\\.netease\\.cloudmusic\\.[a-z0-9]{1,2}\\.[a-z]\\.[a-z]$");
                    Pattern pattern3 = Pattern.compile("^com\\.netease\\.cloudmusic\\.module\\.main\\.[a-z]$");
                    List<String> list = ClassHelper.getFilteredClasses(pattern, Collections.reverseOrder());
                    list.addAll(ClassHelper.getFilteredClasses(pattern2, Collections.reverseOrder()));
                    list.addAll(ClassHelper.getFilteredClasses(pattern3, Collections.reverseOrder()));
                    clazz = Stream
