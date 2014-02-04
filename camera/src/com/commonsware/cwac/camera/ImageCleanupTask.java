package com.commonsware.cwac.camera;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Build;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

public class ImageCleanupTask extends Thread {
  private byte[] data;
  private int cameraId;
  private PictureTransaction xact=null;
  private boolean applyMatrix=true;

  ImageCleanupTask(Context ctxt, byte[] data, int cameraId,
                   PictureTransaction xact) {
    this.data=data;
    this.cameraId=cameraId;
    this.xact=xact;

    float heapPct=(float)data.length / calculateHeapSize(ctxt);

    applyMatrix=(heapPct < xact.host.maxPictureCleanupHeapUsage());
  }

  @Override
  public void run() {
    Camera.CameraInfo info=new Camera.CameraInfo();

    Camera.getCameraInfo(cameraId, info);

    Matrix matrix=null;
    Bitmap cleaned=null;

    if (applyMatrix) {
      if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        if (xact.host.getDeviceProfile().portraitFFCFlipped()
            && (xact.displayOrientation == 90 || xact.displayOrientation == 270)) {
          matrix=flip(new Matrix());
        }
        else if (xact.mirrorFFC()) {
          matrix=mirror(new Matrix());
        }
      }

      try {
        Metadata md=
            ImageMetadataReader.readMetadata(new BufferedInputStream(
                                                                     new ByteArrayInputStream(
                                                                                              data)),
                                             true);
        ExifIFD0Directory exifDir=
            md.getDirectory(ExifIFD0Directory.class);
        int exifOrientation=0;

        if (exifDir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
          exifOrientation=
              exifDir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        }

        int imageOrientation;

        if (exifOrientation == 6) {
          imageOrientation=90;
        }
        else if (exifOrientation == 8) {
          imageOrientation=270;
        }
        else if (exifOrientation == 3) {
          imageOrientation=180;
        }
        else if (exifOrientation == 1) {
          imageOrientation=0;
        }
        else {
          imageOrientation=
              xact.host.getDeviceProfile().getDefaultOrientation();
        }

        if (imageOrientation != 0) {
          matrix=
              rotate((matrix == null ? new Matrix() : matrix),
                     imageOrientation);
        }
      }
      catch (Exception e) {
        Log.w(getClass().getSimpleName(),
              "Exception parsing JPEG byte array", e);
      }

      if (matrix != null) {
        Bitmap original=
            BitmapFactory.decodeByteArray(data, 0, data.length);

        cleaned=
            Bitmap.createBitmap(original, 0, 0, original.getWidth(),
                                original.getHeight(), matrix, true);
        original.recycle();
      }
    }

    if (xact.needBitmap) {
      if (cleaned == null) {
        cleaned=BitmapFactory.decodeByteArray(data, 0, data.length);
      }

      xact.host.saveImage(xact, cleaned);
    }

    if (xact.needByteArray) {
      if (matrix != null) {
        ByteArrayOutputStream out=
            new ByteArrayOutputStream(cleaned.getWidth()
                * cleaned.getHeight());

        cleaned.compress(Bitmap.CompressFormat.JPEG, 100, out);
        data=out.toByteArray();

        try {
          out.close();
        }
        catch (IOException e) {
          Log.e(CameraView.TAG, "Exception in closing a BAOS???", e);
        }
      }

      xact.host.saveImage(xact, data);
    }

    System.gc();
  }

  // from http://stackoverflow.com/a/8347956/115145

  private Matrix mirror(Matrix input) {
    float[] mirrorY= { -1, 0, 0, 0, 1, 0, 0, 0, 1 };
    Matrix matrixMirrorY=new Matrix();

    matrixMirrorY.setValues(mirrorY);
    input.postConcat(matrixMirrorY);

    return(input);
  }

  private Matrix flip(Matrix input) {
    float[] mirrorY= { -1, 0, 0, 0, 1, 0, 0, 0, 1 };
    Matrix matrixMirrorY=new Matrix();

    matrixMirrorY.setValues(mirrorY);
    input.preScale(1.0f, -1.0f);
    input.postConcat(matrixMirrorY);

    return(input);
  }

  private Matrix rotate(Matrix input, int degree) {
    input.setRotate(degree);

    return(input);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private static int calculateHeapSize(Context ctxt) {
    ActivityManager am=
        (ActivityManager)ctxt.getSystemService(Context.ACTIVITY_SERVICE);
    int memoryClass=am.getMemoryClass();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      if ((ctxt.getApplicationInfo().flags & ApplicationInfo.FLAG_LARGE_HEAP) != 0) {
        memoryClass=am.getLargeMemoryClass();
      }
    }

    return(memoryClass * 1048576); // MB * bytes in MB
  }
}