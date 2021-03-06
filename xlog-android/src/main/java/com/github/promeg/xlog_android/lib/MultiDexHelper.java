package com.github.promeg.xlog_android.lib;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.promegu.xlog.base.MethodToLog;
import com.promegu.xlog.base.XLog;
import com.promegu.xlog.base.XLogMethod;
import com.promegu.xlog.base.XLogSetting;
import com.promegu.xlog.base.XLogUtils;
import com.taobao.android.dexposed.ClassUtils;
import com.taobao.android.dexposed.XposedHelpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dalvik.system.DexFile;

/**
 * Created by xudshen@hotmail.com on 14/11/13.
 */
public final class MultiDexHelper {

    private MultiDexHelper() {
        //no instance
    }

    private static final String TAG = "MultiDexHelper";

    private static final String EXTRACTED_NAME_EXT = ".classes";

    private static final String EXTRACTED_SUFFIX = ".zip";

    private static final String SECONDARY_FOLDER_NAME = "code_cache" + File.separator
            + "secondary-dexes";

    private static final String PREFS_FILE = "multidex.version";

    private static final String KEY_DEX_NUMBER = "dex.number";

    private static SharedPreferences getMultiDexPreferences(Context context) {
        return context.getSharedPreferences(PREFS_FILE,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB
                        ? Context.MODE_PRIVATE
                        : Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }


    /**
     * get all the dex path
     *
     * @param context the application context
     * @return all the dex path
     */
    public static List<String> getSourcePaths(Context context)
            throws PackageManager.NameNotFoundException, IOException {
        ApplicationInfo applicationInfo = context.getPackageManager()
                .getApplicationInfo(context.getPackageName(), 0);
        File sourceApk = new File(applicationInfo.sourceDir);
        File dexDir = new File(applicationInfo.dataDir, SECONDARY_FOLDER_NAME);

        List<String> sourcePaths = new ArrayList<String>();
        sourcePaths.add(applicationInfo.sourceDir); //add the default apk path

        //the prefix of extracted file, ie: test.classes
        String extractedFilePrefix = sourceApk.getName() + EXTRACTED_NAME_EXT;
        //the total dex numbers
        int totalDexNumber = getMultiDexPreferences(context).getInt(KEY_DEX_NUMBER, 1);

        for (int secondaryNumber = 2; secondaryNumber <= totalDexNumber; secondaryNumber++) {
            //for each dex file, ie: test.classes2.zip, test.classes3.zip...
            String fileName = extractedFilePrefix + secondaryNumber + EXTRACTED_SUFFIX;
            File extractedFile = new File(dexDir, fileName);
            if (extractedFile.isFile()) {
                sourcePaths.add(extractedFile.getAbsolutePath());
                //we ignore the verify zip part
            } else {
                throw new IOException("Missing extracted secondary dex file '"
                        + extractedFile.getPath() + "'");
            }
        }

        return sourcePaths;
    }

    /**
     * get all the classes name in "classes.dex", "classes2.dex", ....
     *
     * @param context the application context
     * @return all the classes
     */
    private static List<Class<?>> getAllXLogClasses(Context context, XLogSetting xLogSetting)
            throws PackageManager.NameNotFoundException, IOException {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        List<String> sourcePaths = getSourcePaths(context);
        for (String path : sourcePaths) {
            try {
                DexFile dexfile = null;
                if (path.endsWith(EXTRACTED_SUFFIX)) {
                    //NOT use new DexFile(path), because it will throw "permission error in /data/dalvik-cache"
                    dexfile = DexFile.loadDex(path, path + ".tmp", 0);
                } else {
                    dexfile = new DexFile(path);
                }
                Enumeration<String> dexEntries = dexfile.entries();
                while (dexEntries.hasMoreElements()) {
                    String className = dexEntries.nextElement();
                    if (XLogUtils.filterResult(className, xLogSetting)) {
                        try {
                            classes.add(ClassUtils.getClass(className));
                        } catch (Throwable e) {
                            Log.d(TAG, "class not found: " + className);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IOException("Error at loading dex file '"
                        + path + "'");
            }
        }

        Set<String> classNamesFromSetting = new HashSet<String>(xLogSetting.xlogClassNames);
        Set<String> remainClassNames = XLogUtils
                .getRemainingClassNames(new HashSet<Class<?>>(classes), classNamesFromSetting);
        if (remainClassNames != null) {
            for (String className : remainClassNames) {
                try {
                    classes.add(ClassUtils.getClass(className));
                } catch (Throwable e) {
                    Log.d(TAG, "class not found when process remain classes: " + className);
                }
            }
        }

        return classes;
    }

    public static XLogSetting getXLogSetting(Context context, String xLogPkgName)
            throws PackageManager.NameNotFoundException, IOException {
        long startTime = System.currentTimeMillis();
        List<String> sourcePaths = getSourcePaths(context);

        List<String> xlogClassNames = new ArrayList<String>();
        Set<String> xLoggerMethodsClassNames = new HashSet<String>();
        Set<MethodToLog> methodToLogs = new HashSet<MethodToLog>();

        for (String path : sourcePaths) {
            try {
                DexFile dexfile = null;
                if (path.endsWith(EXTRACTED_SUFFIX)) {
                    //NOT use new DexFile(path), because it will throw "permission error in /data/dalvik-cache"
                    dexfile = DexFile.loadDex(path, path + ".tmp", 0);
                } else {
                    dexfile = new DexFile(path);
                }
                Enumeration<String> dexEntries = dexfile.entries();
                String xlogClassPrefix = xLogPkgName + "." + XLogUtils.CLASS_NAME;
                while (dexEntries.hasMoreElements()) {
                    String className = dexEntries.nextElement();
                    if (className != null && className
                            .startsWith(xlogClassPrefix)) {
                        try {
                            Class xlogClass = ClassUtils.getClass(className);

                            xLoggerMethodsClassNames.add(className);

                            String classesStr = (String) XposedHelpers
                                    .findField(xlogClass, XLogUtils.FIELD_NAME_CLASSES).get(null);
                            xlogClassNames.addAll(new Gson().<List<String>>fromJson(classesStr,
                                    new TypeToken<List<String>>() {
                                    }.getType()));

                            String methodsStr = (String) XposedHelpers
                                    .findField(xlogClass, XLogUtils.FIELD_NAME_METHODS).get(null);
                            methodToLogs.addAll(new Gson().<List<MethodToLog>>fromJson(methodsStr,
                                    new TypeToken<List<MethodToLog>>() {
                                    }.getType()));
                        } catch (Throwable t) {
                            //ignore
                        }
                    }
                }
            } catch (IOException e) {
                throw new IOException("Error at loading dex file '" + path + "'");
            }
        }

        Log.d(TAG, "getXLogSetting() called with " + "context = [" + context + "], xLogPkgName = ["
                + xLogPkgName + "]  time: " + (System.currentTimeMillis() - startTime));

        return new XLogSetting(methodToLogs, xLoggerMethodsClassNames,
                XLogUtils.getPkgPrefixesForCoarseMatch(xlogClassNames, 2), xlogClassNames);
    }

    public static Set<Member> getAllMethodsWithAnnoation(Context context,
            Class<? extends Annotation> annoationClass, XLogSetting xLogSetting,
            List<XLogMethod> xLogMethods) {
        long startTime = System.currentTimeMillis();
        try {
            List<Class<?>> allXLogClasses = getAllXLogClasses(context, xLogSetting);
            Set<Member> allMethodsWithAnnoation = new LinkedHashSet<Member>();
            for (Class entryClass : allXLogClasses) {
                if (entryClass != null) {
                    // find annotated methods
                    try {
                        Method[] methods = entryClass.getDeclaredMethods();
                        for (Method method : methods) {
                            if (method.isAnnotationPresent(annoationClass) || XLogUtils
                                    .shouldLogMember(xLogMethods, method)) {
                                allMethodsWithAnnoation.add(method);
                            }
                        }
                    } catch (Throwable t) {
                        //ignore
                    }

                    try {
                        Constructor[] constructors = entryClass.getDeclaredConstructors();
                        for (Constructor constructor : constructors) {
                            if (constructor.isAnnotationPresent(annoationClass) || XLogUtils
                                    .shouldLogMember(xLogMethods, constructor)) {
                                allMethodsWithAnnoation.add(constructor);
                            }
                        }
                    } catch (Throwable t) {
                        //ignore
                    }

                    if (entryClass.isAnnotationPresent(XLog.class)) {
                        // class is annotated with XLog
                        // add all non-inherited methods
                        // TODO add modifier filter
                        allMethodsWithAnnoation
                                .addAll(Arrays.asList(entryClass.getDeclaredMethods()));
                        allMethodsWithAnnoation
                                .addAll(Arrays.asList(entryClass.getDeclaredConstructors()));
                    }
                }
            }

            Log.d(TAG, "getAllMethodsWithAnnoation() called with " + "context = [" + context
                    + "], annoationClass = [" + annoationClass + "], xLogSetting = [" + xLogSetting
                    + "]  time: " + (System.currentTimeMillis() - startTime));
            return allMethodsWithAnnoation;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
