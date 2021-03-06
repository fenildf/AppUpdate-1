package io.github.skyhacker2.updater;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

/**
 * Created by eleven on 2016/10/25.
 */

public class Updater {
    private static final String TAG = Updater.class.getSimpleName();
    private static final String PREF_DOWNLOAD_ID = "pref_download_id";
    private static final String PREF_ONLINE_VERSION_CODE = "pref_prev_version_code";
    private static final String PREF_DOWNLOAD_PATH = "pref_download_path";
    private static final String PREF_JSON = "pref_json";
    // 更新模式
    public static final int INSTALL_MODE_APP = 0;      // 应用内
    public static final int INSTALL_MODE_STORE = 1;    // 应用商店
    public static final int INSTALL_MODE_BROWSER = 2;  // 跳到浏览器下载

    public static final String ACTION_ONLINE_PARAMS_UPDATED = "io.github.skyhacker2.updater.ACTION_ONLINE_PARAMS_UPDATED";

    private static Updater mInstance;
    private Activity mContext;
    private String mAppID;
    private int mVersionCode;
    private String mVersionName;
    private String mAppName;
    private boolean mUpdating;
    private long mDownloadId;
    private DownloadManager mDownloadManager;
    private Handler mHandler;
    private String mUpdateUrl;
    private boolean mDebug = false;
    private String mChannelKey = "UMENG_CHANNEL";
    private boolean mInstallFromStore = false;
    private int mInstallMode = INSTALL_MODE_APP;

    BroadcastReceiver mDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(DownloadManager.ACTION_DOWNLOAD_COMPLETE)) {
                mContext.getApplicationContext().unregisterReceiver(mDownloadReceiver);
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == mDownloadId) {
                    Log.d(TAG, "下载完成");
                    mUpdating = false;
                    Uri uri = checkIfAlreadyExist();
                    if (uri != null) {
                        Log.d(TAG, "uri " + uri);
                        installApk(uri);
                    }
                }
            }
        }
    };

    class UpdateThread extends Thread {
        private JSONObject mOnlineAppInfo;
        public UpdateThread() {

        }

        @Override
        public void run() {
            getOnlineVersion();
            if (mOnlineAppInfo != null) {
                if (( mOnlineAppInfo.optInt("versionCode") > mVersionCode) || mDebug ) {
                    Log.d(TAG, "有新版本可以更新");
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);

                            builder.setTitle(getString("app_update_title"));
                            builder.setMessage(mOnlineAppInfo.optString("updateMessage"));
                            builder.setPositiveButton(getString("app_update_ok"), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    startUpdate();
                                }
                            });
                            builder.setNegativeButton(getString("app_update_later"), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                    mUpdating = false;
                                }
                            });
                            builder.setCancelable(false);
                            builder.create().show();
                        }
                    });
                }
            }
        }

        public void getOnlineVersion() {
            String address = mUpdateUrl;
            try {
                URL url = new URL(address);
                URLConnection connection = url.openConnection();
                InputStream input = connection.getInputStream();
                byte[] buffer = new byte[1024];
                int len = 0;
                byte[] combined = new byte[0];
                while ((len=input.read(buffer)) != -1) {
                    byte[] tmp = new byte[combined.length + len];
                    System.arraycopy(combined,0,tmp,0,combined.length);
                    System.arraycopy(buffer,0,tmp,combined.length,len);
                    combined = tmp;
                }
                String json = new String(combined, "utf-8");
                Log.d(TAG, "online version json " + json);
                mOnlineAppInfo = new JSONObject(json);
                mInstallMode = mOnlineAppInfo.optInt("installMode");
                Log.d(TAG, "online versionCode " + mOnlineAppInfo.optString("versionCode"));
                Log.d(TAG, "online versionName " + mOnlineAppInfo.optString("versionName"));
                Log.d(TAG, "install mode " + mInstallMode);
                JSONObject onlineParams = mOnlineAppInfo.optJSONObject("onlineParams");
                OnlineParams.setParams(onlineParams);
                mContext.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent();
                        intent.setAction(ACTION_ONLINE_PARAMS_UPDATED);
                        mContext.sendBroadcast(intent);
                    }
                });
                SharedPreferences.Editor editor = getSharedPreferencesEditor();
                editor.putString(PREF_JSON, mOnlineAppInfo.toString());
                editor.apply();
//                if (mOnlineAppInfo.getCode() == 0) {
//                    editor.putInt(PREF_ONLINE_VERSION_CODE, mOnlineAppInfo.getVersionCode());
//                    editor.apply();
//                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        private boolean checkPermission() {
            return ActivityCompat.checkSelfPermission(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        public void startDownload() {
            if (!checkPermission()) {
                Toast.makeText(mContext, "没有写入SD卡权限", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(mContext.getPackageName(), PackageManager.GET_META_DATA);
                String channel = info.metaData.getString(mChannelKey);
                JSONObject channels = mOnlineAppInfo.optJSONObject("channels");
                if (channels != null) {
                    String downloadURL = channels.optString(channel);
                    if (TextUtils.isEmpty(downloadURL)){
                        downloadURL = channels.optString("source");
                    }
                    if (!TextUtils.isEmpty(downloadURL)){
                        // 注册下载完成广播
                        IntentFilter filter = new IntentFilter();
                        filter.addAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
                        mContext.getApplicationContext().registerReceiver(mDownloadReceiver, filter);
                        Toast.makeText(mContext, getString("app_update_start"), Toast.LENGTH_LONG).show();
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadURL));
                        request.setTitle(mAppName);
                        String[] patterns = downloadURL.split("/");
                        String fileName = patterns[patterns.length-1];
                        request = request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                        long id = mDownloadManager.enqueue(request);
                        mDownloadId = id;
                        SharedPreferences.Editor editor = getSharedPreferencesEditor();
                        editor.putInt(PREF_ONLINE_VERSION_CODE, mOnlineAppInfo.optInt("versionCode"));
                        editor.putLong(PREF_DOWNLOAD_ID, mDownloadId);
                        File folder = new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
                        File downloadPath = new File(folder, fileName);
                        editor.putString(PREF_DOWNLOAD_PATH, downloadPath.getAbsolutePath());

                        editor.apply();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }


        }

        public void startUpdate() {
            if (mInstallFromStore || mInstallMode == INSTALL_MODE_STORE) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + mContext.getPackageName()));
                PackageManager packageManager = mContext.getPackageManager();
                List activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                boolean intentSafe = activities.size() > 0;
                if (intentSafe) {
                    mContext.startActivity(intent);
                }
            } else if (mInstallMode == INSTALL_MODE_BROWSER) {  // 打开浏览器下载安装包的方式
                try {
                    ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(mContext.getPackageName(), PackageManager.GET_META_DATA);
                    String channel = info.metaData.getString(mChannelKey);
                    JSONObject channels = mOnlineAppInfo.optJSONObject("channels");
                    if (channels != null) {
                        String downloadURL = channels.optString(channel);
                        if (TextUtils.isEmpty(downloadURL)) {
                            downloadURL = channels.optString("source");
                        }
                        if (!TextUtils.isEmpty(downloadURL)) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadURL));
                            mContext.startActivity(intent);
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            } else {
                int saveVersionCode = getSharedPreferences().getInt(PREF_ONLINE_VERSION_CODE, -1);
                // 上次有保存
                if (saveVersionCode == mOnlineAppInfo.optInt("versionCode")) {
                    Uri uri = checkIfAlreadyExist();
                    if (uri != null) {
                        Log.d(TAG, "使用已经存在的安装包 " + uri);
                        installApk(uri);
                    } else {
                        startDownload();
                    }
                } else {
                    startDownload();
                }
            }
        }
    }


    private Updater(){



    }

    private String getString(String key) {
        int id = mContext.getResources().getIdentifier(key, "string", mContext.getPackageName());
        return mContext.getResources().getString(id);
    }

    public static Updater getInstance(Activity context) {
        if (mInstance == null) {
            mInstance = new Updater();
        }
        mInstance.setContext(context);

        return mInstance;
    }

    public static void destroy() {
        if (mInstance != null) {
            mInstance.setContext(null);
        }
    }

    public Context getContext() {
        return mContext;
    }

    public void setContext(Activity context) {
        mContext = context;
        if (mContext != null) {
            mDownloadManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
            mHandler = new Handler(mContext.getMainLooper());
            String json = getSharedPreferences().getString(PREF_JSON, null);
            if (json != null) {
                try {
                    JSONObject object = new JSONObject(json);
                    JSONObject params = object.optJSONObject("onlineParams");
                    OnlineParams.setParams(params);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            Log.d(TAG, "package " + mContext.getPackageName());
        }
    }

    public String getAppID() {
        return mAppID;
    }

    public Updater setAppID(String appID) {
        mAppID = appID;
        return mInstance;
    }

    public void checkUpdate() {
        checkUpdate(false);
    }

    public void checkUpdate(boolean goToStore) {
        Log.d(TAG, "开始检查App更新");
        mInstallFromStore = goToStore;

//        mContext.registerReceiver(mDownloadReceiver, filter);

//        if (!isWifi()) {
//            Log.e(TAG, "移动网络");
//            return;
//        }


        PackageManager manager = mContext.getPackageManager();
        try {
            PackageInfo packageInfo = manager.getPackageInfo(mContext.getPackageName(), 0);
            mVersionCode = packageInfo.versionCode;
            mVersionName = packageInfo.versionName;
            mAppName = packageInfo.applicationInfo.name;
            Log.d(TAG, "current versionCode " + mVersionCode);
            Log.d(TAG, "current versionName " + mVersionName);
            int preOnlineVersionCode = getSharedPreferences().getInt(PREF_ONLINE_VERSION_CODE, -1);
            Log.d(TAG, "preOnlineVersionCode " + preOnlineVersionCode);
            if (preOnlineVersionCode == mVersionCode) {
                // 删除已安装的安装包
                Uri uri = checkIfAlreadyExist();
                if (uri != null) {
                    deleteApk(getSharedPreferences().getLong(PREF_DOWNLOAD_ID, -1));
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "package info not found");
            return;
        }

        synchronized (this) {
            if (!mUpdating) {
                mUpdating = true;
                new UpdateThread().start();
            }
        }

    }

    public String getUpdateUrl() {
        return mUpdateUrl;
    }

    public void setUpdateUrl(String updateUrl) {
        mUpdateUrl = updateUrl;
    }

    public int getInstallMode() {
        return mInstallMode;
    }

    public void setInstallMode(int installMode) {
        mInstallMode = installMode;
    }

    //// 内部函数
    private boolean isWifi() {
        ConnectivityManager manager = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager.getActiveNetworkInfo();
        if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI) {
            return true;
        }
        return false;
    }

    private SharedPreferences getSharedPreferences() {
        SharedPreferences sharedPreferences = mContext.getSharedPreferences("Updater", Context.MODE_PRIVATE);
        return sharedPreferences;
    }

    private SharedPreferences.Editor getSharedPreferencesEditor() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        return sharedPreferences.edit();
    }

    private void installApk(Uri uri) {
        Intent apkIntent = new Intent(Intent.ACTION_VIEW);
        apkIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Log.d(TAG, "uri " + uri);
        apkIntent.setDataAndType(uri, "application/vnd.android.package-archive");
        mContext.startActivity(apkIntent);
    }

    private void deleteApk(long id) {
        Log.d(TAG, "删除安装包");
        mDownloadManager.remove(id);
    }

    private Uri checkIfAlreadyExist() {
        String downloadPath = getSharedPreferences().getString(PREF_DOWNLOAD_PATH, null);
        Log.d(TAG, "downloadPath " + downloadPath);
        if (downloadPath != null) {
            File apkFile = new File(downloadPath);
            if (apkFile.exists()) {
                return getFileUri(apkFile);
            } else {
                return null;
            }
        }
        return null;
    }

    private Uri getFileUri(File file) {
        Log.d(TAG, "authorities " + mContext.getPackageName() + ".fileprovider");
        if (Build.VERSION.SDK_INT >= 24) {
            return FileProvider.getUriForFile(mContext, mContext.getPackageName() + ".fileprovider", file);
        } else {
            return Uri.parse("file://" + file.getAbsolutePath());
        }
    }

    public boolean isDebug() {
        return mDebug;
    }

    public void setDebug(boolean debug) {
        mDebug = debug;
    }
}
