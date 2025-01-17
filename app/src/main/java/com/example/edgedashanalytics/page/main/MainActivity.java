package com.example.edgedashanalytics.page.main;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.example.edgedashanalytics.BuildConfig;
import com.example.edgedashanalytics.R;
import com.example.edgedashanalytics.data.result.ResultRepository;
import com.example.edgedashanalytics.data.video.ExternalStorageVideosRepository;
import com.example.edgedashanalytics.data.video.ProcessingVideosRepository;
import com.example.edgedashanalytics.data.video.VideosRepository;
import com.example.edgedashanalytics.model.BitmapFrame;
import com.example.edgedashanalytics.model.Result;
import com.example.edgedashanalytics.model.Video;
import com.example.edgedashanalytics.page.adapter.ProcessingAdapter;
import com.example.edgedashanalytics.page.adapter.RawAdapter;
import com.example.edgedashanalytics.page.setting.SettingsActivity;
import com.example.edgedashanalytics.util.dashcam.DashCam;
import com.example.edgedashanalytics.util.file.FileManager;
import com.example.edgedashanalytics.util.hardware.PowerMonitor;
import com.example.edgedashanalytics.util.nearby.Endpoint;
import com.example.edgedashanalytics.util.nearby.NearbyFragment;
import com.example.edgedashanalytics.util.video.ImageUtils;
import com.example.edgedashanalytics.util.video.eventhandler.ProcessingVideosEventHandler;
import com.example.edgedashanalytics.util.video.eventhandler.RawVideosEventHandler;
import com.example.edgedashanalytics.util.video.eventhandler.ResultEventHandler;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity implements
        VideoFragment.Listener, ResultsFragment.Listener, NearbyFragment.Listener,
        ImageReader.OnImageAvailableListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    public static final String I_TAG = "Important";

    private VideoFragment rawFragment;
    private VideoFragment processingFragment;
    private ResultsFragment resultsFragment;
    private ConnectionFragment connectionFragment;
    private CameraConnectionFragment cameraFragment;

    private final FragmentManager supportFragmentManager = getSupportFragmentManager();
    private Fragment activeFragment;

    BitmapFrame frame;
    private int frameIndex = 0;

    private final BottomNavigationView.OnItemSelectedListener bottomNavigationOnItemSelectedListener
            = new BottomNavigationView.OnItemSelectedListener() {
        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            int itemId = item.getItemId();

            if (itemId == R.id.navigation_raw) {
                Log.v(TAG, "Navigation raw button clicked");
                showNewFragmentAndHideOldFragment(rawFragment);
                return true;
            } else if (itemId == R.id.navigation_processing) {
                Log.v(TAG, "Navigation processing button clicked");
                showNewFragmentAndHideOldFragment(processingFragment);
                return true;
            } else if (itemId == R.id.navigation_completed) {
                Log.v(TAG, "Navigation completed button clicked");
                showNewFragmentAndHideOldFragment(resultsFragment);
                Toast.makeText(MainActivity.this, String.format("Result count: %s", resultsFragment.getItemCount()),
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }
    };

    private void showNewFragmentAndHideOldFragment(Fragment newFragment) {
        supportFragmentManager.beginTransaction().hide(activeFragment).show(newFragment).commit();
        activeFragment = newFragment;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkPermissions();
        scanVideoDirectories();
        setToolBarAsTheAppBar();
        setUpBottomNavigation();
        setUpFragments();
        FileManager.initialiseDirectories();
        storeLogsInFile();
        DashCam.setup(this);
    }

    int previewHeight = 0,previewWidth = 0;
    int sensorOrientation;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setCameraFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight();
                                previewWidth = size.getWidth();
                                Log.d("tryOrientation","rotation: "+rotation+"   orientation: "+getScreenOrientation()+"  "+previewWidth+"   "+previewHeight);
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this,
                        R.layout.fragment_camera,
                        new Size(640, 480));

        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        supportFragmentManager.beginTransaction().add(R.id.main_container, fragment, "5").commit();
    }
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;
    @Override
    public void onImageAvailable(ImageReader reader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        if (rgbBytes == null) {
            rgbBytes = new int[previewWidth * previewHeight];
        }
        try {
            final Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter =
                    new Runnable() {
                        @Override
                        public void run() {
                            ImageUtils.convertYUV420ToARGB8888(
                                    yuvBytes[0],
                                    yuvBytes[1],
                                    yuvBytes[2],
                                    previewWidth,
                                    previewHeight,
                                    yRowStride,
                                    uvRowStride,
                                    uvPixelStride,
                                    rgbBytes);
                        }
                    };

            postInferenceCallback =
                    new Runnable() {
                        @Override
                        public void run() {
                            image.close();
                            isProcessingFrame = false;
                        }
                    };

            processImage();

        } catch (final Exception e) {
            Log.d("tryError",e.getMessage());
            return;
        }

    }

    private void processImage() {
        imageConverter.run();
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        //Do your work here
        frame = new BitmapFrame(rgbFrameBitmap, frameIndex++);
        addFrame(frame);
        getNextTransfer();
        postInferenceCallback.run();
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            Runtime.getRuntime().exec("logcat -c");
        } catch (IOException e) {
            Log.e(I_TAG, String.format("Unable to clear logcat:\n%s", e.getMessage()));
        }
    }

    private void storeLogsInFile() {
        int id = android.os.Process.myPid();
        @SuppressWarnings("SpellCheckingInspection")
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date());
        String logPath = String.format("%s/%s.log", FileManager.getLogDirPath(), timestamp);

        try {
            // Clear logcat buffer
            Runtime.getRuntime().exec("logcat -c");
            // Write logcat messages to logPath
            String loggingCommand = String.format("logcat --pid %s -f %s", id, logPath);
            Log.v(TAG, String.format("Running logging command: %s", loggingCommand));
            Runtime.getRuntime().exec(loggingCommand);
        } catch (IOException e) {
            Log.e(I_TAG, String.format("Unable to store log in file:\n%s", e.getMessage()));
        }
    }

    private void checkPermissions() {
        String[] PERMISSIONS = {
                Manifest.permission.INTERNET,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NFC,
                Manifest.permission.CAMERA
        };

        if (lacksPermissions(PERMISSIONS)) {
            requestPermissions(PERMISSIONS, 1);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            String[] ANDROID_12_PERMISSIONS = {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.MANAGE_EXTERNAL_STORAGE
            };
            if (lacksPermissions(ANDROID_12_PERMISSIONS)) {
                requestPermissions(ANDROID_12_PERMISSIONS, 2);
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            String[] ANDROID_13_PERMISSIONS = {
                    Manifest.permission.NEARBY_WIFI_DEVICES
            };
            if (lacksPermissions(ANDROID_13_PERMISSIONS)) {
                requestPermissions(ANDROID_13_PERMISSIONS, 3);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Uri uri = Uri.parse("package:" + BuildConfig.APPLICATION_ID);
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
            startActivity(intent);
        }
    }

    private boolean lacksPermissions(String... permissions) {
        if (permissions != null) {
            for (String permission : permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
        }
        return false;
    }

    private void scanVideoDirectories() {
        MediaScannerConnection.OnScanCompletedListener scanCompletedListener = (path, uri) ->
                Log.d(TAG, String.format("Scanned %s\n  -> uri=%s", path, uri));

        MediaScannerConnection.scanFile(this, new String[]{FileManager.getRawDirPath()},
                null, scanCompletedListener);
    }

    private void setToolBarAsTheAppBar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("EDA");
        toolbar.setTitleTextColor(getColor(R.color.white));
        setSupportActionBar(toolbar);
    }

    private void setUpBottomNavigation() {
        BottomNavigationView bottomNavigation = findViewById(R.id.navigation);
        bottomNavigation.setOnItemSelectedListener(bottomNavigationOnItemSelectedListener);
    }

    private void setUpFragments() {
        VideosRepository rawRepository = new ExternalStorageVideosRepository(this, FileManager.getRawDirPath());
        VideosRepository processingRepository = new ProcessingVideosRepository();
        ResultRepository resultRepository = new ResultRepository();

        connectionFragment = new ConnectionFragment();
        rawFragment = VideoFragment.newInstance(ActionButton.ADD,
                new RawVideosEventHandler(rawRepository), RawAdapter::new);
        processingFragment = VideoFragment.newInstance(ActionButton.REMOVE,
                new ProcessingVideosEventHandler(processingRepository), ProcessingAdapter::new);
        resultsFragment = ResultsFragment.newInstance(ActionButton.OPEN, new ResultEventHandler(resultRepository));


        setCameraFragment();
        supportFragmentManager.beginTransaction().add(R.id.main_container, connectionFragment, "4").hide(connectionFragment).commit();
        supportFragmentManager.beginTransaction().add(R.id.main_container, resultsFragment, "3").hide(resultsFragment).commit();
        supportFragmentManager.beginTransaction().add(R.id.main_container, processingFragment, "2").hide(processingFragment).commit();
        supportFragmentManager.beginTransaction().add(R.id.main_container, rawFragment, "1").commit();



        rawFragment.setRepository(rawRepository);
        processingFragment.setRepository(processingRepository);
        resultsFragment.setRepository(resultRepository);

        activeFragment = rawFragment;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == R.id.action_connect) {
            Log.v(TAG, "Connect button clicked");
            checkWifiStrength();
            showNewFragmentAndHideOldFragment(connectionFragment);
            return true;
        } else if (itemId == R.id.action_download) {
            Log.v(TAG, "Download button clicked");
            Toast.makeText(this, "Starting download", Toast.LENGTH_SHORT).show();
            DashCam.downloadTestVideosLoop(this);
            return true;
        } else if (itemId == R.id.action_clean) {
            Log.v(TAG, "Clean button clicked");
            Toast.makeText(this, "Cleaning directories", Toast.LENGTH_SHORT).show();
            cleanDirectories();
            return true;
        } else if (itemId == R.id.action_power) {
            Log.v(TAG, "Power button clicked");
            PowerMonitor.startPowerMonitor(this);
            Toast.makeText(this,
                    String.format(Locale.ENGLISH, "Average power: %dmW", PowerMonitor.getAveragePowerMilliWatts()),
                    Toast.LENGTH_SHORT).show();
            PowerMonitor.printSummary();
            return true;
        } else if (itemId == R.id.action_settings) {
            Log.v(TAG, "Setting button clicked");
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivity(settingsIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void cleanDirectories() {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);

        if (pref.getBoolean(getString(R.string.remove_raw_key), false)) {
            rawFragment.cleanRepository(this);
        }

        processingFragment.cleanRepository(this);
        resultsFragment.cleanRepository();
        FileManager.cleanDirectories(this);
    }

    private void checkWifiStrength() {
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int level;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            level = wifiManager.calculateSignalLevel(wifiInfo.getRssi());
        } else {
            int numberOfLevels = 5;
            level = WifiManager.calculateSignalLevel(wifiInfo.getRssi(), numberOfLevels);
        }

        String signalMessage = String.format("Signal strength: %s, Speed: %s MB/s", level, DashCam.latestDownloadSpeed);
        Log.v(TAG, signalMessage);
        Toast.makeText(this, signalMessage, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean getIsConnected() {
        return isConnected();
    }

    @Override
    public void getAddVideo(Video video) {
        addVideo(video);
    }

    @Override
    public void getNextTransfer() {
        nextTransfer();
    }

    @Override
    public Runnable simulateDownloads(int delay, Consumer<Video> downloadCallback, boolean dualDownload) {
        return rawFragment.simulateDownloads(delay, downloadCallback, dualDownload);
    }

    @Override
    public void onListFragmentInteraction(Result result) {
    }

    @Override
    public void connectEndpoint(Endpoint endpoint) {
        connectionFragment.connectEndpoint(endpoint);
    }

    @Override
    public void disconnectEndpoint(Endpoint endpoint) {
        connectionFragment.disconnectEndpoint(endpoint);
    }

    @Override
    public void removeEndpoint(Endpoint endpoint) {
        connectionFragment.removeEndpoint(endpoint);
    }

    @Override
    public boolean isConnected() {
        return connectionFragment.isConnected();
    }

    @Override
    public void addVideo(Video video) {
        connectionFragment.addVideo(video);
    }

    public void addFrame(BitmapFrame frame){
        connectionFragment.addFrame(frame);
    }

    @Override
    public void nextTransfer() {
        connectionFragment.nextTransfer();
    }

    @Override
    public Runnable getSimulateDownloads(int delay, Consumer<Video> downloadCallback, boolean dualDownload) {
        return simulateDownloads(delay, downloadCallback, dualDownload);
    }
}
