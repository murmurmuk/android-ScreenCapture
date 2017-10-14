package murmur.mediaprojection;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.Button;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Action;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity{
    private final int MY_PERMISSIONS_REQUEST_WRITE_STORAGE = 5566;
    private static final int REQUEST_CODE = 5588;
    private MediaProjectionManager mProjectionManager;
    private MediaProjection mProjection;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader.OnImageAvailableListener mImageListener;
    private MediaScannerConnection.OnScanCompletedListener mScanListener;
    private Context mContext;
    private Button mButton;

    private void initState(){
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mButton.setClickable(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @android.support.annotation.NonNull String permissions[],
                                           @android.support.annotation.NonNull int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_WRITE_STORAGE: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initState();
                }
                break;
            }
        }
    }


    private void askWritePermission(){
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_WRITE_STORAGE);
        }
        else{
            initState();
        }
    }
    private Bitmap createBitmap(Image image){
        Log.d("kanna", "check create bitmap: " + Thread.currentThread().toString());
        Bitmap bitmap;
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        // create bitmap
        bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride,
                image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        image.close();
        return bitmap;
    }

    private Flowable<Image> getScreenShot(){
        final Point screenSize = new Point();
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        Display display = getWindowManager().getDefaultDisplay();
        display.getSize(screenSize);
        return Flowable.create(new FlowableOnSubscribe<Image>() {
            @Override
            public void subscribe(@NonNull final FlowableEmitter<Image> emitter) throws Exception {
                mImageReader = ImageReader.newInstance(screenSize.x, screenSize.y, PixelFormat.RGBA_8888, 2);
                mVirtualDisplay = mProjection.createVirtualDisplay("cap", screenSize.x, screenSize.y, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mImageReader.getSurface(), null, null);
                mImageListener = new ImageReader.OnImageAvailableListener() {
                    Image image = null;
                    @Override
                    public void onImageAvailable(ImageReader imageReader) {
                        try {
                            image = imageReader.acquireLatestImage();
                            Log.d("kanna", "check reader: " + Thread.currentThread().toString());
                            if (image != null) {
                                emitter.onNext(image);
                                emitter.onComplete();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            emitter.onError(new Throwable("ImageReader error"));
                        }
                        mImageReader.setOnImageAvailableListener(null, null);
                    }

                };
                mImageReader.setOnImageAvailableListener(mImageListener, null);

            }
        },BackpressureStrategy.DROP);
    }
    private Flowable<String> createFile(){
        return Flowable.create(new FlowableOnSubscribe<String>() {
            @Override
            public void subscribe(@NonNull FlowableEmitter<String> emitter) throws Exception {
                Log.d("kanna", "check create filename: " + Thread.currentThread().toString());
                String directory, fileHead, fileName;
                int count = 0;
                File externalFilesDir = getExternalFilesDir(null);
                if (externalFilesDir != null) {
                    directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath() + "/screenshots/";

                    Log.d("kanna", directory);
                    File storeDirectory = new File(directory);
                    if (!storeDirectory.exists()) {
                        boolean success = storeDirectory.mkdirs();
                        if (!success) {
                            emitter.onError(new Throwable("failed to create file storage directory."));
                            return;
                        }
                    }
                } else {
                    emitter.onError(new Throwable("failed to create file storage directory," +
                            " getExternalFilesDir is null."));
                    return;
                }

                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
                Calendar c = Calendar.getInstance();
                fileHead = simpleDateFormat.format(c.getTime()) + "_";
                fileName = directory + fileHead + count + ".png";
                File storeFile = new File(fileName);
                while (storeFile.exists()) {
                    count++;
                    fileName = directory + fileHead + count + ".png";
                    storeFile = new File(fileName);
                }
                emitter.onNext(fileName);
                emitter.onComplete();
            }
        },BackpressureStrategy.DROP).subscribeOn(Schedulers.io());
    }
    private void writeFile(Bitmap bitmap, String fileName) throws IOException{
        Log.d("kanna", "check write file: " + Thread.currentThread().toString());
        FileOutputStream fos = new FileOutputStream(fileName);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.close();
        bitmap.recycle();
    }
    private Flowable<String> updateScan(final String fileName){
        return Flowable.create(new FlowableOnSubscribe<String>() {
            @Override
            public void subscribe(@NonNull final FlowableEmitter<String> emitter) throws Exception {
                String[] path = new String[]{fileName};
                mScanListener = new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String s, Uri uri) {
                        Log.d("kanna", "check scan file: " + Thread.currentThread().toString());
                        emitter.onNext("complete scan " + s);
                        emitter.onComplete();
                    }
                };
                MediaScannerConnection.scanFile(mContext, path, null, mScanListener);
            }
        },BackpressureStrategy.DROP);
    }

    /*
    RXJava
     */
    private void shotScreen(){
        getScreenShot()
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(Schedulers.io())
                .map(new Function<Image, Bitmap>() {
                    @Override
                    public Bitmap apply(@NonNull Image image) throws Exception {
                        return createBitmap(image);
                    }
                })
                .zipWith(createFile(), new BiFunction<Bitmap, String, String>() {
                    @Override
                    public String apply(@NonNull Bitmap bitmap, @NonNull String fileName) throws Exception {
                        writeFile(bitmap, fileName);
                        return fileName;
                    }
                })
                .flatMap(new Function<String, Publisher<String>>() {
                    @Override
                    public Publisher<String> apply(@NonNull String fileName) throws Exception {
                        return updateScan(fileName);
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(new Action() {
                    @Override
                    public void run() throws Exception {
                        if (mVirtualDisplay != null){
                            mVirtualDisplay.release();
                        }
                        if (mImageReader != null){
                            mImageReader = null;
                        }
                        if(mImageListener != null){
                            mImageListener = null;
                        }
                        if(mScanListener != null){
                            mScanListener = null;
                        }
                        if(mProjection != null){
                            mProjection.stop();
                            mProjection = null;
                        }
                    }
                })
                .subscribe(new Subscriber<String>() {

                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String filename) {
                        Log.d("kanna", "onNext: " + filename);
                    }

                    @Override
                    public void onError(Throwable t) {
                        Log.w("kanna", "onError: ", t);
                    }

                    @Override
                    public void onComplete() {
                        Log.d("kanna", "onComplete");
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            mProjection = mProjectionManager.getMediaProjection(resultCode, data);
            if(mProjection != null) {
                shotScreen();
            }
        }
    }

    private void screenShot(){
        if(mProjectionManager != null) {
            startActivityForResult(mProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_main);
        mButton = findViewById(R.id.tmp);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                screenShot();
            }
        });
        askWritePermission();
    }

}
