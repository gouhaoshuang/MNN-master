
package com.taobao.android.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
//import android.support.v4.app.ActivityCompat;
import androidx.core.app.ActivityCompat;
/**
 * Description:
 */
public class PermissionUtils {


    public static void askPermission(Activity context, String[] permissions, int req, Runnable
            runnable) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean granted = true;
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                runnable.run();
            } else {
                ActivityCompat.requestPermissions(context, permissions, req);
            }
        } else {
            runnable.run();
        }
    }

    public static void onRequestPermissionsResult(boolean isReq, int[] grantResults, Runnable
            okRun, Runnable deniRun) {
        if (isReq) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            if (granted) {
                okRun.run();
            } else {
                deniRun.run();
            }
        }
    }

}
