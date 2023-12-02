package com.example.shellapk;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dalvik.system.DexClassLoader;

public class ProxyApplication extends Application {
    String TAG = "demo";
    public String apkFileName;
    public String libPath;
    public String dexPath;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SourceApk Application onCreate: " + this);

        //Application实例存在于: LoadedApk中的mApplication字段
        // 以及ActivityThread中的mInitialApplication和mAllApplications和mBoundApplication字段
        //替换Application

        //
        String appClassName = null;
        try {
            //获取AndroidManifest.xml 文件中的 <meta-data> 元素
            ApplicationInfo applicationInfo = getPackageManager().getApplicationInfo(this.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metaData = applicationInfo.metaData;
            //获取xml文件声明的Application类
            if (metaData != null && metaData.containsKey("APPLICATION_CLASS_NAME")){
                appClassName = metaData.getString("APPLICATION_CLASS_NAME");
            } else {
                Log.d(TAG, "xml文件中没有声明Application类名");
                //是因为没有自定义application就不好动态加载源程序的application吗?
                return;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, Log.getStackTraceString(e));
        }
        //xml文件中存在自定义application类
        //开始替换

        //获取ActivityThread实例
        ClassLoader classLoader = this.getClassLoader();
        try {
            //获取ActivityThread类
            Class<?> ActivityThreadClass = classLoader.loadClass("android.app.ActivityThread");
            Log.d(TAG, "ActivityThreadClass: " + ActivityThreadClass.toString());
            //反射获取sCurrentActivityThread实例
            Field sCurrentActivityThreadField = ActivityThreadClass.getDeclaredField("sCurrentActivityThread");
            Log.d(TAG, "sCurrentActivityThread: " + sCurrentActivityThreadField.toString());
            sCurrentActivityThreadField.setAccessible(true);
            Object sCurrentActivityThreadObj = sCurrentActivityThreadField.get(null);//为什么这里可以设置为null
            Log.d(TAG, "直接反射获取ActivityThread字段: " + sCurrentActivityThreadObj.toString());

            //获取mBoundApplication字段 (AppBindData对象)
            Field mBoundApplicationField = ActivityThreadClass.getDeclaredField("mBoundApplication");
            mBoundApplicationField.setAccessible(true);
            Object mBoundApplicationObj = mBoundApplicationField.get(sCurrentActivityThreadObj);

            //获取mBoundApplication对象中的info (LoadedApk对象)
            //所以这个和之前通过mPackages字段获取LoadedApk有什么不同???
            //首先获取AppBindData类,它位于ActivityThread类内部
            Class<?> AppBindDataClass = classLoader.loadClass("android.app.ActivityThread$AppBindData");
            Field infoField = AppBindDataClass.getDeclaredField("info");
            infoField.setAccessible(true);
            Object infoObj = infoField.get(mBoundApplicationObj);
            Log.d(TAG, "infoObj: " + infoObj.toString());

            //把infoObj (LoadedApk对象)中的mApplication设置为null,这样后续才能调用makeApplication()!!!
            Class<?> LoadedApkClass = classLoader.loadClass("android.app.LoadedApk");
            Field mApplicationField = LoadedApkClass.getDeclaredField("mApplication");
            mApplicationField.setAccessible(true);
            Log.d(TAG, "mApplication: " + mApplicationField.get(infoObj).toString());
            mApplicationField.set(infoObj, null);

            //获取ActivityThread实例中的mInitialApplication字段,拿到旧的Application(对于要加载的Application来讲)
            //为什么不直接通过刚才的info获取???
            Field mInitialApplicationField = ActivityThreadClass.getDeclaredField("mInitialApplication");
            mInitialApplicationField.setAccessible(true);
            Object mInitialApplicationObj = mInitialApplicationField.get(sCurrentActivityThreadObj);
            Log.d(TAG, "mInitialApplicationObj: " + mInitialApplicationObj.toString());

            //获取ActivityThread实例中的mAllApplications字段,然后删除里面的mInitialApplication,也就是旧的application
            Field mAllApplicationsField = ActivityThreadClass.getDeclaredField("mAllApplications");
            mAllApplicationsField.setAccessible(true);
            ArrayList<Application> mAllApplicationsObj = (ArrayList<Application>)mAllApplicationsField.get(sCurrentActivityThreadObj);
            mAllApplicationsObj.remove(mInitialApplicationObj);
            Log.d(TAG, "mInitialApplication 从 mAllApplications 中移除成功");

            //这是要干嘛???
            //获取LoadedApk的mApplicationInfo字段
            Field mApplicationInfoField = LoadedApkClass.getDeclaredField("mApplicationInfo");
            mApplicationInfoField.setAccessible(true);
            ApplicationInfo appinfoInLoadedApk = (ApplicationInfo) mApplicationInfoField.get(infoObj);
            Log.d(TAG, "appinfoInLoadedApk: " + appinfoInLoadedApk.toString());


            //获取mBoundApplication对象中的appInfo
            Field appInfoField = AppBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            ApplicationInfo appinfoInAppBindData = (ApplicationInfo) appInfoField.get(mBoundApplicationObj);
            Log.d(TAG, "appinfoInLoadedApk: " + appinfoInAppBindData.toString());


            //设置两个appinfo的classname为源程序的application类名,以便后续调用makeApplication()创建源程序的application
            appinfoInLoadedApk.className = appClassName;
            appinfoInAppBindData.className = appClassName;
            Log.d(TAG, "要加载的源程序application类为: " + appClassName);

            //反射调用makeApplication方法创建源程序的application
            Method makeApplicationMethod = LoadedApkClass.getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
            Log.d(TAG, "makeApplicationMethod: " + makeApplicationMethod.toString());
            makeApplicationMethod.setAccessible(true);
            Application app = (Application) makeApplicationMethod.invoke(infoObj, false, null);
            Log.d(TAG, "创建源程序application成功");

            //将刚创建的Application设置到ActivityThread的mInitialApplication字段
            mInitialApplicationField.set(sCurrentActivityThreadObj, app);
            Log.d(TAG, "源程序的application成功设置到mInitialApplication字段");

            //ContentProvider会持有代理的Application,需要特殊处理一下
            Field mProviderMapField = ActivityThreadClass.getDeclaredField("mProviderMap");
            Log.d(TAG, "mProviderMapField: " + mProviderMapField.toString());
            mProviderMapField.setAccessible(true);
            ArrayMap mProviderMapObj = (ArrayMap) mProviderMapField.get(sCurrentActivityThreadObj);
            Log.d(TAG, "mProviderMapObj: " + mProviderMapObj.toString());
            //获取所有provider,装进迭代器中遍历
            Iterator iterator = mProviderMapObj.values().iterator();
            Log.d(TAG, "iterator: " + iterator.toString());
            while(iterator.hasNext()){
                Object providerClientRecord = iterator.next();
                //获取ProviderClientRecord中的mLocalProvider字段
                Class<?> ProviderClientRecordClass = classLoader.loadClass("android.app.ActivityThread$ProviderClientRecord");
                Field mLocalProviderField = ProviderClientRecordClass.getDeclaredField("mLocalProvider");
                Log.d(TAG, "mLocalProviderField: " + mLocalProviderField.toString());
                mLocalProviderField.setAccessible(true);

                Object mLocalProviderObj = mLocalProviderField.get(providerClientRecord);
                //mLocalProviderObj可能为空
                if(mLocalProviderObj != null){
                    Log.d(TAG, "mLocalProviderObj: " + mLocalProviderObj.toString());
                    //获取ContentProvider中的mContext字段,设置为新建的Application
                    Class<?> ContentProviderClass = classLoader.loadClass("android.content.ContentProvider");
                    Field mContextField = ContentProviderClass.getDeclaredField("mContext");
                    mContextField.setAccessible(true);
                    mContextField.set(mLocalProviderObj,app);
                }

            }
            Log.d(TAG, "app: " + app);
            //开始Application的创建,源程序启动!
            app.onCreate();

        } catch (ClassNotFoundException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }


    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            //在应用程序的数据存储目录下创建文件夹，具体路径为data/user/0/包名/app_payload_dex(怎么多了app_?)
            File dex = getDir("payload_dex", MODE_PRIVATE);
            File lib = getDir("payload_lib", MODE_PRIVATE);
            dexPath = dex.getAbsolutePath();
            libPath = lib.getAbsolutePath();
            Log.d(TAG, "dexPath: " + dexPath);
            Log.d(TAG, "libPath: " + libPath);
            apkFileName = dex.getAbsolutePath() + File.separator + "Source.apk";
            Log.d(TAG, "apkFileName: " + apkFileName);
            // 根据文件路径创建File对象
            File dexFile = new File(apkFileName);
            if (!dexFile.exists()) {
                // 根据路径创建文件，即在payload_dex目录下创建Source.apk文件
                dexFile.createNewFile();
                //读取Classes.dex文件
                byte[] shellDexData = readDexFromApk();
                //从中分理处源apk文件
                splitSourceApkFromShellDex(shellDexData);
            }

            //配置加载源程序的动态环境,即替换mClassLoader
            replaceClassLoaderInLoadedApk();

        } catch (Exception e) {
            Log.d(TAG, "attachBaseContext: " + Log.getStackTraceString(e));

        }
    }

    //替换LoadedApk中的mClassLoader
    private void replaceClassLoaderInLoadedApk() throws Exception{
        // 获取应用程序当前的classloader
        ClassLoader classLoader = this.getClassLoader();
        Log.d(TAG, "classLoader get: " + classLoader.toString());
        Log.d(TAG, "parent classLoader get: " + classLoader.getParent().toString());
        // 获取ActivityThread类
        Class<?> ActivityThreadClass = classLoader.loadClass("android.app.ActivityThread");
        Log.d(TAG, "ActivityThreadClass: " + ActivityThreadClass.toString());
        // ActivityThread已经实例化了，我们需要通过反射currentActivityThread()方法获取实例，而不是通过类反射创建实例（他都不是同一个实例，创建没屁用）
        // 1.通过反射获取方法，进一步获取ActivityThread实例
//            Method currentActivityThreadMethod = ActivityThreadClass.getDeclaredMethod("currentActivityThread");
//            Log.d(TAG, "currentActivityThreadMethod: " + currentActivityThreadMethod.toString());
//            currentActivityThreadMethod.setAccessible(true);
//            Object sCurrentActivityThreadObj = currentActivityThreadMethod.invoke(null);//为什么这里可以设置为null
//            Log.d(TAG, "反射获取方法，进一步获取ActivityThread实例: " + sCurrentActivityThreadObj.toString());
        // 2.直接反射获取ActivityThread字段
        Field sCurrentActivityThreadField = ActivityThreadClass.getDeclaredField("sCurrentActivityThread");
        Log.d(TAG, "sCurrentActivityThread: " + sCurrentActivityThreadField.toString());
        sCurrentActivityThreadField.setAccessible(true);
        Object sCurrentActivityThreadObj = sCurrentActivityThreadField.get(null);//为什么这里可以设置为null
        Log.d(TAG, "直接反射获取ActivityThread字段: " + sCurrentActivityThreadObj.toString());

        //获取mPackages,类型为ArrayMap<String, WeakReference<LoadedApk>>, 里面存放了当前应用的LoadedApk对象
        Field mPackagesField = ActivityThreadClass.getDeclaredField("mPackages");
        mPackagesField.setAccessible(true);
        //获取当前ActivityThread实例的mPackages字段
        ArrayMap mPackagesObj = (ArrayMap) mPackagesField.get(sCurrentActivityThreadObj);
        Log.d(TAG, "mPackagesObj: " + mPackagesObj.toString());

        //获取mPackages中的当前应用包名
        String currentPackageName = this.getPackageName();
        Log.d(TAG, "currentPackageName: " + currentPackageName);

        // 获取loadedApk实例也有好几种,mInitialApplication mAllApplications mPackages
        // 通过包名获取当前应用的loadedApk实例
        WeakReference weakReference = (WeakReference) mPackagesObj.get(currentPackageName);
        Object loadedApkObj = weakReference.get();
        Log.d(TAG, "loadedApkObj: " + loadedApkObj.toString());

        //动态加载源程序的dex文件,以当前classloader的父加载器作为parent
        DexClassLoader dexClassLoader = new DexClassLoader(apkFileName,dexPath,libPath, classLoader.getParent());
        Log.d(TAG, "dexClassLoader: " + dexClassLoader.toString());
        //替换loadedApk实例中的mClassLoader字段
        Class<?> LoadedApkClass = classLoader.loadClass("android.app.LoadedApk");
        Log.d(TAG, "LoadedApkClass: " + LoadedApkClass.toString());
        Field mClassLoaderField = LoadedApkClass.getDeclaredField("mClassLoader");
        mClassLoaderField.setAccessible(true);
        mClassLoaderField.set(loadedApkObj, dexClassLoader);

        //加载源程序的类
        //可有可无，只是测试看看有没有这个类
        try{
            dexClassLoader.loadClass("com.example.sourceapk.MainActivity");
            Log.d(TAG, "com.example.sourceapk.MainActivity: 类加载成功");
        }catch (ClassNotFoundException e){
            Log.d(TAG, "com.example.sourceapk.MainActivity: " + Log.getStackTraceString(e));
        }

    }

    //从合并后的程序apk中提取合并后的dex文件
    private byte[] readDexFromApk() throws IOException {

        //获取当前应用程序的源码路径(apk),一般是data/app目录下,该目录用于存放用户安装的软件
        String sourceDir = this.getApplicationInfo().sourceDir;
        Log.d(TAG, "this.getApplicationInfo().sourceDir: " + this.getApplicationInfo().sourceDir);
        FileInputStream fileInputStream = new FileInputStream(sourceDir);
        BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
        ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream);
        //用于存放读取到的dex文件
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        while(true){
            //获取apk中的一个个文件
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            //遍历完了apk中的文件
            if (zipEntry == null){
                break;
            }
            // 提取出的文件是classes.dex文件,则读取到bytearray中,显然这里只能处理含单dex文件的apk,多dex的apk待实现
            if (zipEntry.getName().equals("classes.dex")){
                byte[] bytes = new byte[1024];
                while(true){
                    //每次读取1024byte,返回的是读取到的byte数
                    int i = zipInputStream.read(bytes);
                    if (i == -1){
                        break;
                    }
                    //存放到开辟的byteArrayOutputStream中
                    byteArrayOutputStream.write(bytes,0, i);
                }
            }
            //关闭当前条目并定位到apk中的下一个文件
            zipInputStream.closeEntry();
        }
        zipInputStream.close();

        //返回读取到的dex文件
        return byteArrayOutputStream.toByteArray();
    }

    private void splitSourceApkFromShellDex(byte[] shellDexData) throws IOException {
        int shellDexlength = shellDexData.length;
        //开始解析dex文件
        byte[] sourceApkSizeByte = new byte[4];
        //读取源apk的大小
        System.arraycopy(shellDexData,shellDexlength - 4, sourceApkSizeByte,0,4);
        //转成bytebuffer,方便4byte转int
        ByteBuffer wrap = ByteBuffer.wrap(sourceApkSizeByte);
        //将byte转成int, 加壳时,长度我是按小端存储的
        int sourceApkSizeInt = wrap.order(ByteOrder.LITTLE_ENDIAN).getInt();
        //读取源apk
        byte[] sourceApkData = new byte[sourceApkSizeInt];
        //忘记减4了！
        System.arraycopy(shellDexData,shellDexlength - sourceApkSizeInt - 4, sourceApkData, 0, sourceApkSizeInt);
        //解密源apk
        sourceApkData = decryptoSourceApk(sourceApkData);
        //写入新建的apk文件中
        File apkfile = new File(apkFileName);
        try {
            FileOutputStream apkfileOutputStream = new FileOutputStream(apkfile);
            apkfileOutputStream.write(sourceApkData);
            apkfileOutputStream.close();
        }catch (IOException e){
            throw new IOException(e);
        }

        //分析源apk,取出so文件放入libPath目录中
        FileInputStream fileInputStream = new FileInputStream(apkfile);
        BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
        ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream);
        while (true){
            ZipEntry nextEntry = zipInputStream.getNextEntry();
            if (nextEntry == null){
                break;
            }
            String name = nextEntry.getName();
            if (name.startsWith("lib/") && name.endsWith(".so")){
                //获取文件名并创建相应文件
                String[] nameSplit = name.split("/");
                String soFileStorePath = libPath + File.separator + nameSplit[nameSplit.length - 1];
                File storeFile = new File(soFileStorePath);
                storeFile.createNewFile();
                //读数据到相应so文件中
                FileOutputStream fileOutputStream = new FileOutputStream(storeFile);
                byte[] bytes = new byte[1024];
                while(true){
                    int i = zipInputStream.read(bytes);
                    if(i == -1){
                        break;
                    }
                    fileOutputStream.write(bytes,0,i);
                }
                //存储玩so文件后,关闭so文件的输出缓存区
                fileOutputStream.flush();
                fileOutputStream.close();
            }
            zipInputStream.closeEntry();
        }
        zipInputStream.close();

    }

    private byte[] decryptoSourceApk(byte[] sourceApkdata) {
        for (int i = 0; i < sourceApkdata.length; i++){
            sourceApkdata[i] ^= 0xff;
        }
        return sourceApkdata;

    }
}
