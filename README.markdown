CWAC-Camera: Taking Pictures. Made Sensible.
============================================

Taking pictures or videos using a third-party app is fairly straightforward,
using `ACTION_IMAGE_CAPTURE` or `ACTION_VIDEO_CAPTURE`. However, you as the
developer have little control over what happens with the image or video,
other than indicating where the result gets stored. Plus, different camera
apps have slightly different behavior, meaning that you are prone to getting
inconsistent results.

Taking pictures or videos using the built-in `Camera` class directly is
eminently possible, but is full of edge and corner cases, not to mention
its own set of per-device idiosyncracies. As a result, a ton of code is
required to successfully show a preview, take a picture, and take a video.

`CWAC-Camera` is an effort to standardize that "ton of code" and hide it
behind a scalable API. Here, "scalable" means "simple things are simple,
but complex things may be a bit complex".

In addition to what is written here,
JavaDocs [are also available](http://javadocs.commonsware.com/cwac/camera/index.html).

Installation
------------
If you are using Eclipse, Ant, or otherwise need JAR files,
there are two JARs in
[the releases area of the repo](https://github.com/commonsguy/cwac-camera/releases):

- `camera-X.Y.Z.jar` represents the core classes, used in all environments
- `camera-v9-X.Y.Z.jar` adds support for ActionBarSherlock

(where `X.Y.Z` is the version number of the project, such as `0.5.4`)

If you are using Gradle, or otherwise can use AAR artifacts,
there are two such artifacts, mirroring the contents of the two JARs:
one for native API Level 11 fragments, one for ActionBarSherlock.

To integrate the core AAR, the Gradle recipe is:

```groovy
repositories {
    maven {
        url "https://repo.commonsware.com.s3.amazonaws.com"
    }
}

dependencies {
    compile 'com.commonsware.cwac:camera:0.5.4'
}
```

To integrate the `-v9` AAR for ActionBarSherlock support, the
Gradle recipe is:

```groovy
repositories {
    mavenCentral();

    maven {
        url "https://repo.commonsware.com.s3.amazonaws.com"
    }
}

dependencies {
    compile('com.commonsware.cwac:camera-v9:0.5.4') {
      exclude module: 'support-v4'
    }

    compile 'com.android.support:support-v4:18.0.+'
}
```

(where you can choose your own version number for the `support-v4` dependency
as desired)

You are also welcome to clone this repo and use `camera/` and
`camera-v9/` as Android library projects in source form.

If you are upgrading a project using CWAC-Camera to a new edition of the
library, please see
[the "Upgrading" section below](https://github.com/commonsguy/cwac-camera#upgrading).

Basic Usage
-----------

Step #1: Install the JARs or AARs as described above.

Step #2: Add a `CameraFragment` to your UI. You have two versions of
`CameraFragment` to choose from:

- `com.commonsware.cwac.camera.CameraFragment` for use with native
API Level 11+ fragments

- `com.commonsware.cwac.camera.acl.CameraFragment` for use with
the Android Support package's backport of fragments and
[ActionBarSherlock](http://actionbarsherlock.com/), supporting API Level 9
and 10

(note: if you choose the latter, your project will also need
to have the ActionBarSherlock library project)

The `CameraFragment` is responsible for rendering your preview, so
you need to size and position it as desired.

Step #3: Call `takePicture()` on the `CameraFragment` when you want
to take a picture, which will be stored in the default digital photos
directory (e.g., `DCIM`) on external storage as `Photo_yyyyMMdd_HHmmss.jpg`, where
`yyyyMMdd_HHmmss` is replaced by the current date and time.

Step #3b: Call `startRecording()` and `stopRecording()` on the
`CameraFragment` to record a video. **NOTE** that this is presently
only available on `com.commonsware.cwac.camera.CameraFragment`
for use with native API Level 11+ fragments. The resulting video
will be stored in the default videos directory (e.g., `Movies`) on external storage as
 `Video_yyyyMMdd_HHmmss.mp4`, where
`yyyyMMdd_HHmmss` is replaced by the current date and time.

Step #4: Add `android:largeHeap="true"` to the `<application>`
element in the manifest (a requirement which will hopefully be
relaxed in the future).

And that's it.

`CameraFragment` (and its underlying `CameraView`)
will handle:

- Showing the preview using an optimal preview frame size, and
managing the aspect ratio of the on-screen preview `View` so
that your previews do not appear stretched

- Dealing with configuration changes and screen rotation, so
your camera activity can work in portrait or landscape

- Following the appropriate recipes for taking still pictures
and videos, including choosing the largest-available image size
for the resolution

- Opening and closing the camera at the appropriate times, so
when you are in the foreground you have exclusive camera access,
but other apps will have access to the camera while your activity
is not in the foreground

- And more!

Simple Configuration and Usage
------------------------------
Of course, there are probably plenty of things that you will want to configure
about the process of taking photos and videos. There are many hooks in `CWAC-Camera`
to allow you to do just that.

Much of this configuration involves creating a custom `CameraHost`. `CameraHost`
is your primary interface with the `CWAC-Camera` classes for configuring
the behavior of the camera. `CameraHost` is an interface, one that you are
welcome to implement in full. Most times, though, you will be better served
extending `SimpleCameraHost`, the default implementation of `CameraHost`,
so that you can override only those methods where you want behavior different
from the default.

Given a customized `CameraHost` implementation, you can pass an instance
of that to `setHost()` on your `CameraFragment`, to replace the default.
**Do this in `onCreate()` of a `CameraFragment` subclass** (or, if practical,
just after instantiating your fragment) to ensure that the
right `CameraHost` is used everywhere.

### Controlling the Names and Locations of Output Files

There are a series of methods that you can override on `SimpleCameraHost`
to control where photos and videos
are stored once taken. These methods will be called for each `takePicture()`
or `startRecording()` call, so you can create customized results for each
distinct photo or video.

Specifically:

- Override `getPhotoFilename()` to return the base name of the file to use
to store the photo

- Override `getPhotoDirectory()` to return the name of the directory in which
to store the photo

- Override `getPhotoPath()` to return the complete `File` object pointing
to the desired file in the desired directory (the default implementation
combines the results of `getPhotoDirectory()` and `getPhotoFilename()`, so
overriding `getPhotoPath()` replaces all of that)

There are equivalent `getVideoFilename()`, `getVideoDirectory()`, and
`getVideoPath()` for controlling the output of the next video to be taken.

By default, if you are using `SimpleCameraHost`, your image will be indexed
by the `MediaStore`. If you do not want this, override `scanSavedImage()`
to return `false` in your `SimpleCameraHost` subclass. This is called on a
per-image basis.

### Controlling Which Camera is Used

If you override `useFrontFacingCamera()` on `SimpleCameraHost` to return
`true`, the front-facing camera will be used, instead of the default rear-facing
camera.

Or, override `getDeviceId()` (available on `CameraHost`), and you can provide
the ID of the specific camera you want. This would involve your choosing an
available camera based on your own criteria. See the JavaDocs for Android's
`Camera` class, notably
[`getNumberOfCameras()`](http://developer.android.com/reference/android/hardware/Camera.html#getNumberOfCameras())
and [`getCameraInfo()`](http://developer.android.com/reference/android/hardware/Camera.html#getCameraInfo(int, android.hardware.Camera.CameraInfo))
for more.

### Controlling FFC Mirror Correction

By default, the pictures taken from the front-facing camera are a mirror
image of what is shown on the preview. If you wish for the front-facing
camera photos to match the preview, override `mirrorFFC()` on your `CameraHost`
and have it
return `true`, and `CWAC-Camera` will reverse the image for you before
saving it.

### Handling Exceptions

There are some exceptions that are thrown by the `Camera` class (and kin, like
`MediaRecorder`). Those are passed to your host's `handleException()`
method. The default implementation displays a `Toast` and logs the message
to LogCat as an error, but you probably will want to replace that with
something else that integrates better with your UI.

### Wrapping the Preview UI

From a UI standpoint, the `CameraFragment` solely handles the preview pane.
Presumably, you will need
more to your UI than this, such as buttons to allow users to take pictures or
record videos. You have two major options here:

1. You can put that UI as a peer to the `CameraFragment`, such as by having action
bar items, as the demo apps do.

2. You can subclass `CameraFragment` and override `onCreateView()`. Chain to the
superclass to get the `CameraFragment`'s own UI, then wrap that in your own
container with additional widgets, and return the combined UI from your `onCreateView()`.
You can see this in the main demo app, which adds a `SeekBar` or `VerticalSeekBar`
for zoom levels.

It is also possible to replace `onCreateView()` completely with your own
implementation, or otherwise use `CameraView` from a layout resource. This is
covered later in this document.

### Auto-Focus

You can call `autoFocus()` on `CameraFragment` or `CameraView` to trigger any
auto-focus behavior that you have configured via `setFocusMode()` on `Camera.Parameters`.
You can call
`cancelAutoFocus()` on `CameraFragment` or `CameraView` to ensure that auto-focus
mode has been canceled.

Note that auto-focus is only available in certain conditions, notably when
the preview mode is enabled. You can call `isAutoFocusAvailable()` on `CameraFragment`
or `CameraView` to determine if auto-focus is presently available for use. Calling
`autoFocus()` when auto-focus is not available will have no effect.

`CameraHost` implementations will need to implement an `onAutoFocus()` method, coming
from
[the `Camera.AutoFocusCallback` interface](https://developer.android.com/reference/android/hardware/Camera.AutoFocusCallback.html)
that `CameraHost` extends.
`SimpleCameraHost` has a default implementation of `onAutoFocus()` that
plays a
device-standard sound upon completion (API Level 16+ only).

`CameraHost` implementations will also need `autoFocusAvailable()` and
`autoFocusUnavailable()` methods, to be notified when auto-focus is available or
not. This can be used to trigger whether action bar items are enabled, etc.
`SimpleCameraHost` has no-op implementations of these callbacks.

### Single-Shot Mode

By default, the result of taking a picture is to return the `CameraFragment`
to preview mode, ready to take the next picture. If, instead, you only need
the one picture, or you want to send the user to some other bit of UI first
and do not want preview to start up again right away, override
`useSingleShotMode()` in your `CameraHost` to return `true`.

You will then
probably want to use your own `saveImage()` implementation in your
`CameraHost` to do whatever you want instead of restarting the preview.
For example, you could start another activity to do something with the
image. However, bear in mind that an `Intent` is limited to ~1MB, and so
passing an image to another activity via a `Intent` extra is likely to be
unreliable. You will need to do something else, such as (carefully) use a
static data member.

Preview mode will re-enable automatically after an `onPause()`/`onResume()`
cycle of your `CameraFragment`, or you can call `restartPreview()` on your
`CameraFragment` (or `CameraView`).

### Zoom Support

To zoom the camera, call `zoomTo()` on the `CameraView` or `CameraFragment`,
supplying the integer zoom level that you want. This level must be between
0 and what `Camera.Parameters` returns from `getMaxZoom()`. The
`adjustPreviewParameters()` callback method in your `CameraHost` is a good
time to get this value and configure your UI (e.g., `SeekBar`) to allow
the user to zoom the camera.

`zoomTo()` returns a `ZoomTransaction`. This has a series of builder-style
methods (a.k.a., a fluent interface) that allow you to configure the
transaction, where the methods return the transaction so you can chain on 
the next call. The configuration methods are:

- `onComplete()` to supply a `Runnable` to be executed when we have reached
the zoom level

- `onChange()` to supply a `Camera.OnZoomChangeListener` to be called as
we progress to the desired zoom level

Once configured, call `go()` to run the transaction.

If the camera supports smooth zoom, the zoom transaction will take a few
moments, and you can cancel the operation by calling `cancel()` on the
`ZoomTransaction`. If the camera does not support smooth zoom, the zoom
level is just immediately changed.

Note that your `OnZoomChangeListener` supplied to `onChange()` will be
called before the `onComplete()` `Runnable`, if you happen to supply both.

The main demo app adds a `SeekBar` and `VerticalSeekBar` to control zoom
levels, so you can see how this is used.

Note that some devices lie about their zoom capabilities. For example,
the Motorola RAZR i's front-facing camera apparently does not support
zoom, where `getMaxZoom()` still returns a positive value.
`doesZoomReallyWork()` on your `CameraFragment` or `CameraView` will
return `false` if zoom is known to be broken for the current camera
on the current device. In this case, do not zoom, or your code may
go "boom".

### Camera? #FAIL

If `getCameraId()` of your `CameraHost` returns a negative value, `CameraView`
will assume that there are no valid cameras (e.g., your app is running on a
game console). In addition to avoiding anything that tries to touch the camera,
your `CameraHost` will be called with `onCameraFail()`, where you will be
supplied with a `FailureReason` of `NO_CAMERAS_REPORTED`.

If anything else goes wrong when trying to open the camera (e.g., a device admin
policy has disabled the camera), your `onCameraFail()` method will be called
with a `FailureReason` of `UNKNOWN`.

While `SimpleCameraHost` has a trivial `onCameraFail()` implementation (just
logging to LogCat), you are strongly encouraged to override this and inform
your users of the problem.

Advanced Configuration
----------------------
In addition to the configuration hooks specified above, you can do more
to tailor how photos and videos are taken.

### Controlling Preview Sizes

Your `CameraHost` will be called with `getRecordingHint()`, to determine if the preview
should be optimized for possible video recording, or not (i.e., only still images will
be taken). You can return a `CameraHost.RecordingHint` enum: `STILL_ONLY`, `VIDEO_ONLY`,
or `ANY`.

Usually, your `CameraHost` will be called with `getPreviewSize()`, where you need to return
a valid `Camera.Size` indicating the desired size of the preview frames. `getPreviewSize()`
is passed:

- the display orientation, in degrees, with 0 indicating landscape, 90 indicating
portrait, etc.

- the available width and height for the preview

- the `Camera.Parameters` object, from which you can determine the valid preview sizes
by calling `getSupportedPreviewSizes()`

The `CameraUtils` class contains three static methods with stock algorithms for
choosing the preview size:

1. `getOptimalPreviewSize()` uses the algorithm found in the SDK camera sample app

2. `getBestAspectPreviewSize()` finds the preview size that most closely matches the
aspect ratio of our available space

3. `getBestAspectPreviewSize(double)` finds the preview size that offers the biggest
preview size that only differs from the desired aspect ratio by the supplied
`closeEnough` value (`closeEnough` of `0.0d` would give the same results as does
`getBestAspectPreviewSize()`)

`SimpleCameraHost` uses `getBestAspectPreviewSize()` for the default implementation
of `getPreviewSize()`. You can override `getPreviewSize()` and substitute in your
own selection algorithm. Just make sure that the returned size is one of the ones
returned by `getSupportedPreviewSizes()`.

If `getRecordingHint()` returns `ANY` or `VIDEO_ONLY`, though,
`CameraHost` supplies the preview
size via `getPreferredPreviewSizeForVideo()` instead of `getPreviewSize()`. If you
wish to use a different preview size for video, return it, otherwise return `null`
and we will use the results from `getPreviewSize()` instead. `getPreferredPreviewSizeForVideo()`
is passed a `Camera.Size` as a hint from the device for a value to use, instead of
anything you might get yourself from `Camera.Parameters` -- while using the hinted
value is probably a good idea (if it is not `null`), it is not required.

### Controlling Picture Sizes

Similarly, your `CameraHost` will be called with `getPictureSize()`, for you to return
the desired `Camera.Size` of the still images taken by the camera. You are simply passed the
`Camera.Parameters`, on which you can call `getSupportedPictureSizes()` to find out
the possible picture sizes that you can choose from.

The `CameraUtils` class has a pair of methods for simple algorithms for choosing a picture
size:

1. `getLargestPictureSize()` returns the `Camera.Size` that is the largest in area

2. `getSmallestPictureSize()` returns the `Camera.Size` that is the smallest in area

`SimpleCameraHost` uses `getLargestPictureSize()` for the default implementation
of `getPictureSize()`. You can override `getPictureSize()` and substitute in your
own selection algorithm. Just make sure that the returned size is one of the ones
returned by `getSupportedPictureSizes()`.

### Arbitrary Preview Configuration

When setting up the camera preview, your `CameraHost` will be called with
`adjustPreviewParameters()`, passing in a `Camera.Parameters`. Here, you can make
any desired adjustments to the camera preview, *except* the preview size (which you
should be handling in `getPreviewSize()`). `adjustPreviewParameters()` returns
the revised `Camera.Parameters`, where the stock implementation in 
`SimpleCameraHost` just returns the passed-in parameters unmodified.

### Arbitrary Photo Configuration

Shortly after you call `takePicture()` on your `CameraFragment`,
your `CameraHost` will be called with
`adjustPictureParameters()`, passing in a `Camera.Parameters`. Here, you can make
any desired adjustments to the parameters related to taking photos,
*except* the image size (which you
should be handling in `getPictureSize()`). `adjustPictureParameters()` returns
the revised `Camera.Parameters`, where the stock implementation in 
`SimpleCameraHost` just returns the passed-in parameters unmodified.

### Arbitrary Video Configuration

Shortly after you call `startRecording()`, your `CameraHost` will be called
with:

- `configureRecorderAudio()`

- `configureRecorderProfile()`

- `configureRecorderOutput()`

in that order. Here, you can help tailor the way videos get recorded.
Each of these is passed the ID of the camera being used for recording plus
the `MediaRecorder` instance that does the actual recording.

The stock `SimpleCameraHost` does the following:

- In `configureRecorderAudio()`, `SimpleCameraHost` calls
`setAudioSource(MediaRecorder.AudioSource.CAMCORDER)` on the `MediaRecorder`

- In `configureRecorderProfile()`, `SimpleCameraHost` calls
`setProfile(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH))`
on the `MediaRecorder`

- In `configureRecorderOutput()`, `SimpleCameraHost` calls
`setOutputFile(getVideoPath().getAbsolutePath())` on the `MediaRecorder`
(where `getVideoPath()` was described earlier in this document)

While these are reasonable defaults, you are welcome to override these
implementations to do something else.

### Overriding Photo Saving

The default `SimpleCameraHost` logic for saving photos uses the `getPhotoPath()` 
and related methods discussed above. Actually saving the photo is done in
`saveImage(byte[])`, called on your `CameraHost`, where `SimpleCameraHost` has a
`saveImage(byte[])` implementation that writes the supplied `byte[]` out to the desired
location.

You are welcome to override `saveImage(byte[])` and do something else with the `byte[]`, 
such as send it over the Internet. `saveImage(byte[])` is called on a background thread,
so you do not have to do your own asynchronous work.

Another use for this is to find out when the saving is complete, so that you can
use the resulting image. Just override `saveImage(byte[])`, chain to the superclass
implementation, and when that returns, the image is ready for use.

There is also a `saveImage(Bitmap)` callback, giving you a decoded `Bitmap`
instead of a `byte[]`. To use this, there is a second version of `takePicture()`
that you can call that takes two `boolean` parameters, indicating whether or
not you want the `saveImage(Bitmap)` callback called and/or the
`saveImage(byte[])` callback called. The zero-argument `takePicture()` indicates
that you only want `saveImage(byte[])` called. If you pass `true` as the
first parameter to the two-parameter `takePicture()` method, then your host
will be called with `saveImage(Bitmap)`. Note that if you do this, you are
responsible for the `Bitmap` (e.g., calling `recycle()` on it) once it is handed
to your host.

### Controlling the Shutter Callback

Your `CameraHost` implementation can return a `Camera.ShutterCallback` object
via `getShutterCallback()`,
which will be used in the underlying `takePicture()` call on the Android `Camera`,
giving you control to play a "shutter click" sound. `SimpleCameraHost` returns `null`
from `getShutterCallback()`, to give you the device default behavior.

### Controlling EXIF Rotation Behavior

Device cameras are generally set up to take landscape pictures. If you try to use
a camera to take a picture in portrait mode, one of three things will happen:

1. Everything works fine, with the device capturing a portrait image

2. The device crashes (this scenario CWAC-Camera aims to handle)

3. The device captures a landscape image, but sets an EXIF header to indicate
that an image viewer should rotate the image to portrait when displaying it

The problem with the last scenario is that not all image viewers will honor
this EXIF header.

Your `CameraHost` will need to implement `rotateBasedOnExif()` to indicate
if you want the library to rotate the image automatically. If you return
`true`, you will get a portrait image from all devices, for all three
of the above scenarios. If you return `false`, the last scenario will
be ignored, and you may get a portrait or a landscape image.

`SimpleCameraHost` returns `true` for `rotateBasedOnExif()`.

### Detecting Faces

If you wish to use the face detection APIs available on API Level 14+, do the
following:

1. Have your `CameraHost` implementation also implement `Camera.FaceDetectionListener`.

2. Override `adjustPreviewParameters()` in your `CameraHost` and take that opportunity
to check the value of `getMaxNumDetectedFaces()`, a method on `Camera.Parameters`.
If that returns 0, face detection is not supported by the device. **NOTE**: a better
API for this may be added in the future.

3. Override `autoFocusAvailable()` in your `CameraHost`, and if face detection is
enabled, call `startFaceDetection()` on your `CameraFragment` or `CameraView`.
**NOTE**: a dedicated callback for this may be added in the future &mdash; this is
a stop-gap to allow this fix to go in a patch release

4. Similarly, override `autoFocusUnavailable()` in your `CameraHost` and, if face
detection is enabled, call `stopFaceDetection()` on your `CameraFragment` or `CameraView`.
**NOTE**: a dedicated callback for this may be added in the future.

Note that this capability was added to version 0.5.1 of this library. Also note
that, while you can safely call `startFaceDetection()` and `stopFaceDetection()`
regardless of API level, `getMaxNumDetectedFaces()` should only be called on API Level
14+ devices, or you will be hit with a `VerifyError` or the equivalent.

### Choosing a DeviceProfile

`CameraHost` exists to provide a hook for you to determine how your app
should handle taking pictures and videos. `DeviceProfile`, on the other hand,
provides information about how the *device* handles taking pictures and videos.
Different devices do slightly different things when working with the camera.
Sometimes this is based on API level, sometimes it is based on how the device
manufacturer tinkered with Android, and sometimes it is based on the underlying
camera hardware. `DeviceProfile` provides a place for the CWAC-Camera project
to isolate these differences.

`CameraHost` has a `getDeviceProfile()` method that should return an instance
of the `DeviceProfile` to use for the device that is running the app.
The implementation of `getDeviceProfile()` on `SimpleCameraHost`
calls the static `getInstance()` method on `DeviceProfile`, which chooses
a `DeviceProfile` based on internal heuristics. If you encounter problems
with certain devices, you can detect those in your `getDeviceProfile()` method
and return a `DeviceProfile` that addresses your needs, otherwise settling for
using the library's own choice of `DeviceProfile`.

At present, there are four methods on `DeviceProfile` that you can tailor
in your subclasses:

- `useTextureView()` should return `true` if `CameraView` should use a
`TextureView` for rendering the preview frames, or `false` if a `SurfaceView`
should be used instead

- `encodesRotationToExif()` indicates if the device puts information about
the device orientation into EXIF headers of the JPEG image

- `rotateBasedOnExif()` should return `true` if the library should attempt to
physically change the orientation of the image if the EXIF orientation header
indicates that the image should be changed, `false` otherwise

- `getMaxPictureHeight()` returns the maximum image height to be selected
by `CameraUtils.getLargestPictureSize()`, to work around devices that report
invalid large `Camera.Size` values

### Working Directly with CameraView

If you wish to eschew fragments, you are welcome to work with `CameraView`
directly. To do this:

- Add it in Java code by calling its one-parameter constructor, taking
your `Activity` as a parameter. At the present time, `CameraView` does not
support being placed in a layout resource.

- Call `setHost()` on the `CameraView` as early as possible, to make sure
that the `CameraView` is working with the right `CameraHost` implementation.
Alternatively, override `getHost()` and return the right `CameraHost`
there.

- Forward the `onResume()` and `onPause()` lifecycle events from your
activity or fragment to the `CameraView`.

Otherwise, `CameraView` should work as a regular `View`... so long as you do
not try to use it in a layout resource.

### Using CameraView in a Layout Resource

If you want to use `CameraView` in a layout resource, you can, but your
activity will need to implement the `CameraHostProvider` interface. This has
one required method: `getCameraHost()`, which returns the `CameraHost` instance
to be used with the `CameraView`. You would implement this in lieu of calling
`setHost()` yourself.

If you want to take advantage of this and use your own layout in a `CameraFragment`
subclass, simply override `onCreateView()` and do what you want. The only
requirement, other than the `CameraHostProvider` mentioned above, is that
your `onCreateView()` needs to call the `setCameraView()` method, supplying
the `CameraView` instance to the superclass.

The `demo-layout/` directory contains a small sample project that demonstrates
this technique.

## Miscellaneous

`CameraView`, as well as `CameraFragment`, has a `getFlashMode()` which
returns the flash mode from `Camera.Parameters`. If you wish to change
the flash mode, please do so in `adjustPictureParameters()` and/or
`adjustPreviewParameters()`.

Third-Party Code
----------------
kenyee has
[an implementation of `CameraFragment`](https://github.com/kenyee/cwac-camera/blob/master/library/src/com/commonsware/cwac/camera/asl/CameraFragment.java)
that simply extends from the
Android Support package's backport of fragments, for use with the AppCompat
backport of the action bar.

Known Limitations
-----------------
These are above and beyond [the bugs filed for this project](https://github.com/commonsguy/cwac-camera/issues):

1. Taking videos in portrait mode will result in the video files still being
stored as landscape, but with a bit in the MPEG-4 header indicating that the
output should be rotated. Unfortunately, many video players ignore this header.
This is a function of how `MediaRecorder` works, and there is no current
workaround in `CWAC-Camera` for this behavior.

2. Taking photos in portrait mode, for some devices, will have a similar
effect: the photo is saved in landscape, with an EXIF field in the JPEG indicating
that the results should be rotated. `CWAC-Camera` detects this and tries to
correct it, so the image is saved in portrait. However, this may consume too
much memory at present, which is why Step #4 above calls for you to add
`android:largeHeap="true"`. This will hopefully be rectified in a future
version of this component.

3. While a picture or video is being taken, on some devices, the aspect
ratio of the preview gets messed up. The aspect ratio is corrected by `CWAC-Camera`
once the picture or video is completed, but more work is needed to try to prevent
this in the first place, or at least mask it a bit better for photos.

4. The Samsung Galaxy Ace refuses to honor a portrait preview in an activity
that itself supports portrait or landscape. If you lock your activity to only
display in landscape, the Galaxy Ace will probably work.

Upgrading
---------
If you are moving from an older to a newer edition of CWAC-Camera, here are some
upgrade notes which may help.

### From 0.4.x to 0.5.0 and Higher

`CameraHost` used to have `mayUseForVideo()`, returning a boolean. That is
now `getRecordingHint()`, returning a `CameraHost.RecordingHint` value: `STILL_ONLY`,
`VIDEO_ONLY`, or `ANY`. `SimpleCameraHost` was modified to return `ANY`, so the
default behavior should be the same as before. Hence, you should only need to worry
about this if you overrode `mayUseForVideo()` or implemented your own `CameraHost`.

`CameraHost` now has an `onCameraFail()` method that takes a `FailureReason`
parameter. `FailureReason` is an `enum`, with values of `NO_CAMERAS_REPORTED`
and `UNKNOWN` at present. This will be called if `CameraView` could not access
a camera. `SimpleCameraHost` has an implementation of `onCameraFail()` that
just logs a message to LogCat, but you are encouraged to supply your own
implementation that does something more.

### From 0.2.x/0.3.0 to 0.4.0 and Higher

`CameraHost` now requires implementers supply `mayUseForVideo()` (`true` if the
preview should be optimized for possible use in video recording) and
`getPreferredPreviewSizeForVideo()` (returns the preview size to use in case
`mayUseForVideo()` returns `true`). `SimpleCameraHost` provides stock implementations
of these, but if you created your own `CameraHost` from scratch, you will need
to add your own versions of these methods.

### From 0.1.x to 0.2.0 and Higher

`CameraHost` now extends `Camera.AutoFocusCallback`, requiring an implementation
of `onAutoFocus()`. `SimpleCameraHost` shows a basic implementation that, on
API Level 16+, plays the device-standard "hey! you're focused now!" sound.

### From 0.0.x to 0.1.0 and Higher

Developers moving from v0.0.x to v0.1.x should note that you now need to pass
a `Context` into the constructor of `SimpleCameraHost`. This can be any `Context`,
as `SimpleCameraHost` retrieves the `Application` singleton from it, so you do not
have to worry about memory leaks.

Tested Devices
--------------
The columns indicate what version of the library that the various devices have
been tested on. The numbers in the columns indicate the Android OS version the
device was running (note: not shown for 0.4.x).

| Device                              | 0.4.x | 0.5.0 | Issues |
| ----------------------------------- |:-----:|:-----:|:------:|
| Acer Iconia Tab A700                | X     | 4.1.1 |        |
| Amazon Kindle Fire HD               | X     | 4.0.4 |        |
| Amazon Kindle Fire HDX 8.9          |       | 4.2.2 | [75](https://github.com/commonsguy/cwac-camera/issues/75)     |
| ASUS MEMO Pad FHD 10                | X     | 4.2.2 |        |
| ASUS Transformer Infinity (TF700)   | X     | 4.2.1 |        |
| Galaxy Nexus                        | X     | 4.3   |        |
| HTC Droid Incredible 2              | X     | 2.3.4 |        |
| HTC One S                           | X     | 4.1.1 | [76](https://github.com/commonsguy/cwac-camera/issues/76)    |
| Lenovo ThinkPad Tablet              | X     | 4.0.3 | [38](https://github.com/commonsguy/cwac-camera/issues/38)    |
| Nexus 4                             | X     | 4.4   |        |
| Nexus 5                             | X     | 4.4   |        |
| Nexus 7 (2012)                      | X     | 4.4   | [72](https://github.com/commonsguy/cwac-camera/issues/72) [73](https://github.com/commonsguy/cwac-camera/issues/73) |
| Nexus 7 (2013)                      | X     | 4.4   | [70](https://github.com/commonsguy/cwac-camera/issues/70)     |
| Nexus 10                            | X     | 4.4   |        |
| Nexus One                           | X     | 2.3.6 |        |
| Nexus S                             | X     | 4.1.2 |        |
| Motorola RAZR i                     | X     | 4.1.2 |        |
| Samsung Galaxy Ace (GT-S5830M)      | X     | 2.3.6 |        |
| Samsung Galaxy Grand (GT-I9090L)    | X     | 4.1.2 |        |
| Samsung Galaxy Note 2 (GT-N7100)    | X     | 4.1.2 | [19](https://github.com/commonsguy/cwac-camera/issues/19)     |
| Samsung Galaxy S3                   | X     | 4.1.2 | [77](https://github.com/commonsguy/cwac-camera/issues/77)     |
| Samsung Galaxy S4 (GT-I9500)        | X     | 4.3   |        |
| Samsung Galaxy S4 (SGH-I337)        | X     | 4.2.2 |        |
| Samsung Galaxy Tab 2 7.0 (GT-P3113) | X     | 4.2.2 |        |
| Samsung Galaxy Tab 10.1 (GT-P7510)  |       | 3.0.1 |        |
| SONY Ericsson Xperia Play           | X     | 2.3.6 |        |
| SONY Xperia E                       | X     | 4.1.1 | [45](https://github.com/commonsguy/cwac-camera/issues/45)     |
| SONY Xperia S LT26i                 | X     | 4.1.2 |        |
| SONY Xperia Z                       | X     | 4.2.2 |        |
| SONY Xperia Z Ultra                 |       | 4.2.2 |        |

Dependencies
------------
This project depends on the Android Support package and ActionBarSherlock
at compile time, if you are using
the Android library project. It also depends on the Android Support package and
ActionBarSherlock at runtime
if you are using the `.acl` flavor of `CameraFragment`.

Version
-------
This is version v0.5.2 of this module, meaning it is rather new.

Demo
----
In the `demo/` sub-project you will find a sample project demonstrating the use
of `CameraFragment` for the native API Level 11 implementation of fragments. The
`demo-v9/` sub-project has a similar sample for the `CameraFragment` that works
with ActionBarSherlock. The `demo-layout/` sub-project demonstrates using
`CameraView` in your own layout resource, with the `CameraHostProvider`
interface and the `setCameraView()` call on the `CameraFragment`.

License
-------
The code in this project is licensed under the Apache
Software License 2.0, per the terms of the included LICENSE
file.

Questions
---------
If you have questions regarding the use of this code, please post a question
on [StackOverflow](http://stackoverflow.com/questions/ask) tagged with `commonsware` and `android`. Be sure to indicate
what CWAC module you are having issues with, and be sure to include source code 
and stack traces if you are encountering crashes.

If you have encountered what is clearly a bug, or if you have a feature request,
please post an [issue](https://github.com/commonsguy/cwac-camera/issues).
Be certain to include complete steps for reproducing the issue.

Do not ask for help via Twitter.

Also, if you plan on hacking
on the code with an eye for contributing something back,
please open an issue that we can use for discussing
implementation details. Just lobbing a pull request over
the fence may work, but it may not.

Release Notes
-------------
- v0.5.4: refactored into two libraries, added Gradle support and AAR artifacts
- v0.5.2: face detection, zoom, and demo bug fixes
- v0.5.1: added face detection support
- v0.5.0: zoom support, layout resource support, JavaDocs, etc.
- v0.4.3: override `getPreferredPreviewSizeForVideo()` &mdash; if too low, use `getPreviewSize()`
- v0.4.2: fixed bug with Droid Incredible 2
- v0.4.1: added `getFlashMode()`, added `DeviceProfile` control over minimum picture height
- v0.4.0: fixed bug in `getBestAspectPreviewSize()`, added hooks for device overrides for video preview sizes, improved support for HTC One
- v0.3.0: improved support for auto-focus, Samsung Galaxy Camera, etc.
- v0.2.1: CyanogenMod devices will now use `SurfaceView` regardless of API level
- v0.2.0: auto-focus support, single-shot mode, Droid Incredible 2 fixes
- v0.1.1: improved support for Nexus 4 and Galaxy Tab 2
- v0.1.0: Nexus S crash fixed, added support for indexing images to `MediaStore`
- v0.0.4: Nexus S EXIF issue fixed, added `saveImage(Bitmap)` callback 
- v0.0.3: shutter callback support, bug fixes
- v0.0.2: bug fixes
- v0.0.1: initial release

Who Made This?
--------------
<a href="http://commonsware.com">![CommonsWare](http://commonsware.com/images/logo.png)</a>

