package com.jinle.serialmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity implements Ch348Device.Listener {
    private static final String ACTION_USB_PERMISSION = "com.jinle.serialmonitor.USB_PERMISSION";
    private static final int FACE_COUNT = 4;
    private static final int LAYERS_PER_FACE = 16;
    private static final int BOARD_COUNT = 8;
    private static final int PORTS_PER_BOARD = 8;
    private static final int CHANNEL_COUNT = FACE_COUNT * LAYERS_PER_FACE;
    private static final long MEASUREMENT_DURATION_MS = 10_000L;
    private static final int USB_MAPPING_SCHEMA = 2;
    private static final String PREF_BOARD_MAPPING = "boardMappingV2";
    private static final String PREF_BOARD_KEY_PREFIX = "boardDeviceKeyV2_";
    private static final String PREF_MAPPING_SCHEMA = "boardMappingSchema";
    private static final char[] CHANNEL_NAMES = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'};
    private static final int[] FACE_COLORS = {
            Color.rgb(22, 132, 101), Color.rgb(36, 116, 181),
            Color.rgb(225, 119, 54), Color.rgb(125, 86, 174)
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
    private final List<Ch348Device> openedDevices = new ArrayList<>();
    private final List<UsbDevice> detectedDevices = new ArrayList<>();
    private final TextView[] distanceViews = new TextView[CHANNEL_COUNT];
    private final TextView[] stateViews = new TextView[CHANNEL_COUNT];
    private final TextView[] mappingViews = new TextView[FACE_COUNT];
    private final long[] receiveCounts = new long[CHANNEL_COUNT];
    private final AtomicLong recordSequence = new AtomicLong();
    private final AtomicBoolean connectionInProgress = new AtomicBoolean(false);
    private final Object recordLock = new Object();

    private UsbManager usbManager;
    private PendingIntent permissionIntent;
    private TextView overallStatus;
    private TextView recordStatus;
    private Button measureButton;
    private Button resetButton;
    private View liveView;
    private LinearLayout resultPanel;
    private BodyContourView contourView;
    private TextView waistResultView;
    private TextView hipResultView;
    private TextView ratioResultView;
    private TextView qualityResultView;
    private int[] boardMapping = {0, 1, 2, 3, 4, 5, 6, 7};
    private final String[] rememberedDeviceKeys = new String[BOARD_COUNT];
    private boolean hasRememberedUsbOrder;
    private boolean restoredByDeviceIdentity;

    private volatile boolean measuring;
    private volatile boolean recording;
    private long measurementEndsAt;
    private MeasurementSession measurementSession = new MeasurementSession();
    private BufferedWriter recordWriter;
    private Uri recordUri;
    private long recordCount;
    private boolean permissionRequestInFlight;
    private boolean destroyed;

    private final Runnable measurementTicker = new Runnable() {
        @Override
        public void run() {
            if (!measuring || destroyed) return;
            long remaining = measurementEndsAt - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                finishMeasurement();
                return;
            }
            double seconds = remaining / 1000.0;
            int total = 0;
            for (int face = 0; face < FACE_COUNT; face++) {
                total += measurementSession.getFaceSampleCount(face);
            }
            measureButton.setText(String.format(Locale.CHINA, "四面测量中 · %.1f秒", seconds));
            recordStatus.setText(String.format(Locale.CHINA, "64路同步采集中 · %.1f秒 · %,d条", seconds, total));
            mainHandler.postDelayed(this, 100);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                permissionRequestInFlight = false;
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (!granted) setOverallStatus("USB权限被拒绝，请点击重新连接", false);
                else scanAndConnect();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scanAndConnect();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (isSupportedDevice(detached)) handleDetach(detached);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        loadBoardMapping();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        permissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        buildUi();
        registerUsbReceiver();
        scanAndConnect();
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(239, 244, 250));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(10), dp(22), dp(10));
        header.setBackgroundColor(Color.rgb(12, 34, 58));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("四面64路腰臀围测量", 26, Color.WHITE, true));
        titles.addView(text("前 / 左 / 后 / 右各16层　·　中心距500 mm　·　635–1235 mm　·　同步测量10秒",
                14, Color.rgb(188, 207, 228), false));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        overallStatus = text("正在检测8块USB转接板…", 16, Color.WHITE, true);
        overallStatus.setPadding(dp(16), dp(9), dp(16), dp(9));
        overallStatus.setBackground(roundRect(Color.rgb(55, 72, 96), 18));
        header.addView(overallStatus);
        root.addView(header);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(16), dp(7), dp(16), dp(7));
        controls.setBackgroundColor(Color.WHITE);
        Button reconnect = button("重新连接", Color.rgb(32, 95, 145));
        reconnect.setOnClickListener(v -> scanAndConnect());
        controls.addView(reconnect);
        Button mapping = button("USB映射", Color.rgb(104, 82, 151));
        mapping.setOnClickListener(v -> showMappingDialog());
        controls.addView(mapping);
        measureButton = button("开始四面测量（10秒）", Color.rgb(10, 124, 102));
        measureButton.setOnClickListener(v -> startMeasurement());
        controls.addView(measureButton);
        resetButton = button("返回实时数据", Color.rgb(190, 72, 64));
        resetButton.setOnClickListener(v -> resetMeasurement());
        controls.addView(resetButton);
        recordStatus = text("等待8块USB转接板全部在线", 15, Color.rgb(76, 91, 110), true);
        recordStatus.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(recordStatus, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(controls);

        ScrollView verticalScroll = new ScrollView(this);
        verticalScroll.setFillViewport(true);
        LinearLayout faces = new LinearLayout(this);
        faces.setOrientation(LinearLayout.HORIZONTAL);
        faces.setPadding(dp(8), dp(7), dp(8), dp(10));
        for (int face = 0; face < FACE_COUNT; face++) {
            faces.addView(makeFaceColumn(face), new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        verticalScroll.addView(faces, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        liveView = verticalScroll;
        root.addView(verticalScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        resultPanel = makeResultPanel();
        resultPanel.setVisibility(View.GONE);
        root.addView(resultPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        updateMappingLabels();
    }

    private View makeFaceColumn(int face) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(9), dp(7), dp(9), dp(9));
        panel.setBackground(roundRect(Color.WHITE, 10));

        TextView title = text(MeasurementSession.FACE_NAMES[face] + " · 16路", 21,
                FACE_COLORS[face], true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        mappingViews[face] = text("", 12, Color.rgb(93, 108, 127), true);
        mappingViews[face].setGravity(Gravity.CENTER);
        mappingViews[face].setPadding(0, 0, 0, dp(4));
        panel.addView(mappingViews[face]);

        for (int layer = 0; layer < LAYERS_PER_FACE; layer++) {
            int index = face * LAYERS_PER_FACE + layer;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(7), dp(2), dp(7), dp(2));
            if ((layer & 1) == 1) row.setBackgroundColor(Color.rgb(246, 249, 252));

            int heightMm = (int) (MeasurementSession.TOP_SENSOR_HEIGHT_MM -
                    MeasurementSession.SENSOR_SPACING_MM * layer);
            TextView label = text(String.format(Locale.CHINA, "%02d层  %d mm", layer + 1, heightMm),
                    14, Color.rgb(65, 83, 105), true);
            distanceViews[index] = text("-- mm", 22, Color.rgb(20, 48, 75), true);
            distanceViews[index].setGravity(Gravity.END);
            stateViews[index] = text("等待数据", 11, Color.rgb(126, 140, 157), false);
            stateViews[index].setGravity(Gravity.END);

            LinearLayout values = new LinearLayout(this);
            values.setOrientation(LinearLayout.VERTICAL);
            values.addView(distanceViews[index]);
            values.addView(stateViews[index]);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.55f));
            row.addView(values, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.45f));
            panel.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        }
        outer.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return outer;
    }

    private LinearLayout makeResultPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackgroundColor(Color.rgb(238, 243, 249));
        contourView = new BodyContourView(this);
        panel.addView(contourView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.VERTICAL);
        metrics.setPadding(dp(26), dp(22), dp(26), dp(22));
        metrics.setBackground(roundRect(Color.WHITE, 10));
        metrics.addView(text("四面同步测量结果", 24, Color.rgb(19, 49, 76), true));
        waistResultView = text("腰围 -- cm", 38, Color.rgb(226, 112, 56), true);
        hipResultView = text("臀围 -- cm", 38, Color.rgb(120, 82, 170), true);
        ratioResultView = text("腰臀比 --", 25, Color.rgb(31, 93, 140), true);
        qualityResultView = text("", 16, Color.rgb(90, 105, 124), false);
        qualityResultView.setPadding(0, dp(14), 0, 0);
        metrics.addView(waistResultView);
        metrics.addView(hipResultView);
        metrics.addView(ratioResultView);
        metrics.addView(qualityResultView);
        panel.addView(metrics, new LinearLayout.LayoutParams(dp(410),
                LinearLayout.LayoutParams.MATCH_PARENT));
        return panel;
    }

    private void scanAndConnect() {
        if (destroyed) return;
        setOverallStatus("正在检测并连接8块CH348…", true);
        List<UsbDevice> found = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isSupportedDevice(device)) found.add(device);
        }
        found.sort(Comparator.comparing(UsbDevice::getDeviceName));
        synchronized (detectedDevices) {
            detectedDevices.clear();
            detectedDevices.addAll(found);
        }
        updateMappingLabels();

        if (found.size() < BOARD_COUNT) {
            setOverallStatus("仅检测到" + found.size() + "/8块CH348", false);
            markAllWaiting("USB未齐");
            recordStatus.setText("请检查8口独立供电Hub、USB线和8块转接板");
            return;
        }
        if (found.size() > BOARD_COUNT) found = new ArrayList<>(found.subList(0, BOARD_COUNT));

        UsbDevice permissionMissing = null;
        for (UsbDevice device : found) {
            if (!usbManager.hasPermission(device)) {
                permissionMissing = device;
                break;
            }
        }
        if (permissionMissing != null) {
            setOverallStatus("请依次允许USB权限", true);
            if (!permissionRequestInFlight) {
                permissionRequestInFlight = true;
                usbManager.requestPermission(permissionMissing, permissionIntent);
            }
            return;
        }
        if (!connectionInProgress.compareAndSet(false, true)) return;

        restoredByDeviceIdentity = restoreBoardMappingFromDeviceKeys(found);
        if (!validMapping(boardMapping)) boardMapping = identityMapping();
        updateMappingLabels();
        final List<UsbDevice> sortedDevices = found;
        final List<UsbDevice> logicalDevices = new ArrayList<>(BOARD_COUNT);
        for (int logicalBoard = 0; logicalBoard < BOARD_COUNT; logicalBoard++) {
            logicalDevices.add(sortedDevices.get(boardMapping[logicalBoard]));
        }
        usbExecutor.execute(() -> openDevices(logicalDevices));
    }

    private void openDevices(List<UsbDevice> devices) {
        closeOpenedDevices();
        List<Ch348Device> newlyOpened = new ArrayList<>();
        try {
            for (int logicalBoard = 0; logicalBoard < devices.size(); logicalBoard++) {
                Ch348Device device = new Ch348Device(usbManager, devices.get(logicalBoard),
                        logicalBoard, this);
                device.open();
                newlyOpened.add(device);
            }
            synchronized (openedDevices) {
                openedDevices.addAll(newlyOpened);
            }
            boolean identityRestored = restoredByDeviceIdentity;
            rememberLogicalDeviceOrder(devices);
            runOnUiThread(() -> {
                setOverallStatus("全部在线 · 64/64", true);
                recordStatus.setText(identityRestored
                        ? "64路已连接 · 已按设备身份恢复USB顺序"
                        : "64路已连接 · USB顺序已保存，首次请核对上/下位置");
                for (int i = 0; i < CHANNEL_COUNT; i++) stateViews[i].setText("已连接");
            });
        } catch (Exception error) {
            for (Ch348Device device : newlyOpened) device.close();
            runOnUiThread(() -> setOverallStatus("连接失败：" + error.getMessage(), false));
        } finally {
            connectionInProgress.set(false);
        }
    }

    private static boolean isSupportedDevice(UsbDevice device) {
        return device != null && device.getVendorId() == Ch348Device.VENDOR_ID &&
                device.getProductId() == Ch348Device.PRODUCT_ID;
    }

    private void showMappingDialog() {
        List<UsbDevice> devices;
        synchronized (detectedDevices) {
            devices = new ArrayList<>(detectedDevices);
        }
        if (devices.size() < BOARD_COUNT) {
            Toast.makeText(this, "检测到8块CH348后才能设置映射", Toast.LENGTH_LONG).show();
            return;
        }
        if (devices.size() > BOARD_COUNT) devices = new ArrayList<>(devices.subList(0, BOARD_COUNT));

        String[] options = new String[BOARD_COUNT];
        for (int i = 0; i < BOARD_COUNT; i++) {
            options[i] = "Hub顺序#" + (i + 1) + " · " + shortDeviceName(devices.get(i));
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(14), dp(8), dp(14), dp(8));
        Spinner[] spinners = new Spinner[BOARD_COUNT];
        for (int logical = 0; logical < BOARD_COUNT; logical++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text(boardSlotName(logical), 16, Color.rgb(33, 54, 77), true);
            spinners[logical] = new Spinner(this);
            spinners[logical].setMinimumHeight(dp(48));
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item, options);
            spinners[logical].setAdapter(adapter);
            spinners[logical].setSelection(boardMapping[logical]);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f));
            row.addView(spinners[logical], new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f));
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("记忆8块USB转接板顺序")
                .setMessage("默认Hub插入顺序：前上、前下、左上、左下、后上、后下、右上、右下。每块设备只能选择一次；保存后APP会自动记忆。")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认顺序", null)
                .setPositiveButton("保存并重连", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                for (int i = 0; i < BOARD_COUNT; i++) spinners[i].setSelection(i);
                Toast.makeText(this, "已恢复前上、前下、左上、左下、后上、后下、右上、右下",
                        Toast.LENGTH_SHORT).show();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                            int[] selected = new int[BOARD_COUNT];
                            for (int i = 0; i < BOARD_COUNT; i++) selected[i] = spinners[i].getSelectedItemPosition();
                            if (!validMapping(selected)) {
                                Toast.makeText(this, "USB设备不能重复选择", Toast.LENGTH_LONG).show();
                                return;
                            }
                            boardMapping = selected;
                            saveBoardMapping();
                            updateMappingLabels();
                            dialog.dismiss();
                            scanAndConnect();
                        });
        });
        dialog.show();
    }

    private void loadBoardMapping() {
        if (getPreferences(MODE_PRIVATE).getInt(PREF_MAPPING_SCHEMA, 0) != USB_MAPPING_SCHEMA) {
            boardMapping = identityMapping();
            return;
        }
        String saved = getPreferences(MODE_PRIVATE).getString(PREF_BOARD_MAPPING, "");
        if (saved == null || saved.isEmpty()) return;
        String[] parts = saved.split(",");
        if (parts.length != BOARD_COUNT) return;
        int[] parsed = new int[BOARD_COUNT];
        try {
            for (int i = 0; i < BOARD_COUNT; i++) parsed[i] = Integer.parseInt(parts[i]);
            if (validMapping(parsed)) boardMapping = parsed;
        } catch (NumberFormatException ignored) {
        }
        hasRememberedUsbOrder = true;
        for (int i = 0; i < BOARD_COUNT; i++) {
            rememberedDeviceKeys[i] = getPreferences(MODE_PRIVATE)
                    .getString(PREF_BOARD_KEY_PREFIX + i, "");
            if (rememberedDeviceKeys[i] == null || rememberedDeviceKeys[i].isEmpty()) {
                hasRememberedUsbOrder = false;
            }
        }
    }

    private void saveBoardMapping() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < BOARD_COUNT; i++) {
            if (i > 0) value.append(',');
            value.append(boardMapping[i]);
        }
        android.content.SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit()
                .putInt(PREF_MAPPING_SCHEMA, USB_MAPPING_SCHEMA)
                .putString(PREF_BOARD_MAPPING, value.toString());
        for (int i = 0; i < BOARD_COUNT; i++) {
            editor.remove(PREF_BOARD_KEY_PREFIX + i);
            rememberedDeviceKeys[i] = "";
        }
        hasRememberedUsbOrder = false;
        editor.apply();
    }

    /** Saves logical positions by unique USB serial number when available, otherwise by device path. */
    private void rememberLogicalDeviceOrder(List<UsbDevice> logicalDevices) {
        if (logicalDevices.size() != BOARD_COUNT) return;
        String[] keys = uniqueDeviceKeys(logicalDevices);
        StringBuilder mappingValue = new StringBuilder();
        for (int i = 0; i < BOARD_COUNT; i++) {
            if (i > 0) mappingValue.append(',');
            mappingValue.append(boardMapping[i]);
        }
        android.content.SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit()
                .putInt(PREF_MAPPING_SCHEMA, USB_MAPPING_SCHEMA)
                .putString(PREF_BOARD_MAPPING, mappingValue.toString());
        for (int logical = 0; logical < BOARD_COUNT; logical++) {
            rememberedDeviceKeys[logical] = keys[logical];
            editor.putString(PREF_BOARD_KEY_PREFIX + logical, keys[logical]);
        }
        hasRememberedUsbOrder = true;
        editor.apply();
    }

    private boolean restoreBoardMappingFromDeviceKeys(List<UsbDevice> sortedDevices) {
        if (!hasRememberedUsbOrder || sortedDevices.size() != BOARD_COUNT) return false;
        String[] currentKeys = uniqueDeviceKeys(sortedDevices);
        int[] restored = new int[BOARD_COUNT];
        Set<Integer> used = new HashSet<>();
        for (int logical = 0; logical < BOARD_COUNT; logical++) {
            int match = -1;
            for (int physical = 0; physical < BOARD_COUNT; physical++) {
                if (!used.contains(physical) && rememberedDeviceKeys[logical].equals(currentKeys[physical])) {
                    match = physical;
                    break;
                }
            }
            if (match < 0) return false;
            restored[logical] = match;
            used.add(match);
        }
        boardMapping = restored;
        return true;
    }

    private static String[] uniqueDeviceKeys(List<UsbDevice> devices) {
        String[] serialKeys = new String[devices.size()];
        String[] result = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice device = devices.get(i);
            try {
                String serial = device.getSerialNumber();
                if (serial != null && !serial.trim().isEmpty()) {
                    serialKeys[i] = "serial:" + device.getVendorId() + ':' +
                            device.getProductId() + ':' + serial.trim();
                }
            } catch (SecurityException ignored) {
            }
        }
        for (int i = 0; i < devices.size(); i++) {
            int matches = 0;
            if (serialKeys[i] != null) {
                for (String candidate : serialKeys) {
                    if (serialKeys[i].equals(candidate)) matches++;
                }
            }
            result[i] = matches == 1 ? serialKeys[i] : "path:" + devices.get(i).getDeviceName();
        }
        return result;
    }

    private static boolean validMapping(int[] mapping) {
        if (mapping == null || mapping.length != BOARD_COUNT) return false;
        Set<Integer> used = new HashSet<>();
        for (int value : mapping) {
            if (value < 0 || value >= BOARD_COUNT || !used.add(value)) return false;
        }
        return true;
    }

    private static int[] identityMapping() {
        return new int[]{0, 1, 2, 3, 4, 5, 6, 7};
    }

    private void updateMappingLabels() {
        if (mappingViews[0] == null) return;
        for (int face = 0; face < FACE_COUNT; face++) {
            int upper = boardMapping[face * 2] + 1;
            int lower = boardMapping[face * 2 + 1] + 1;
            mappingViews[face].setText("上1–8层→#" + upper + "　下9–16层→#" + lower);
        }
    }

    private static String boardSlotName(int logicalBoard) {
        int face = logicalBoard / 2;
        return MeasurementSession.FACE_NAMES[face] +
                (logicalBoard % 2 == 0 ? " 上（1–8层）" : " 下（9–16层）");
    }

    private static String shortDeviceName(UsbDevice device) {
        String name = device.getDeviceName();
        int marker = name.indexOf("/dev/bus/usb/");
        return marker >= 0 ? name.substring(marker + 13) : name;
    }

    @Override
    public void onLine(Ch348Device source, int port, String raw, Double distanceMm) {
        int logicalBoard = source.getLogicalGroup();
        int face = logicalBoard / 2;
        boolean upperBoard = (logicalBoard & 1) == 0;
        int layer = (upperBoard ? 0 : PORTS_PER_BOARD) + port;
        int index = face * LAYERS_PER_FACE + layer;
        if (index < 0 || index >= CHANNEL_COUNT) return;
        long count = ++receiveCounts[index];
        if (measuring && distanceMm != null) measurementSession.addSample(face, layer, distanceMm);
        if (recording && measuring) writeRecord(face, layer, source.getLogicalGroup(), port, raw, distanceMm);

        mainHandler.post(() -> {
            if (distanceMm != null) {
                String value = Math.rint(distanceMm) == distanceMm
                        ? String.format(Locale.CHINA, "%.0f mm", distanceMm)
                        : String.format(Locale.CHINA, "%.1f mm", distanceMm);
                distanceViews[index].setText(value);
                distanceViews[index].setTextColor(Color.rgb(7, 120, 95));
                stateViews[index].setText(String.format(Locale.CHINA, "%,d条", count));
            } else {
                stateViews[index].setText(raw.length() > 14 ? raw.substring(0, 14) + "…" : raw);
            }
        });
    }

    @Override
    public void onError(Ch348Device source, String message, Throwable error) {
        runOnUiThread(() -> setOverallStatus(message, false));
    }

    private void startMeasurement() {
        if (measuring) return;
        int connected;
        synchronized (openedDevices) {
            connected = openedDevices.size();
        }
        if (connected < BOARD_COUNT) {
            Toast.makeText(this, "需要8块CH348全部在线后才能测量64路数据", Toast.LENGTH_LONG).show();
            return;
        }
        measurementSession = new MeasurementSession();
        if (!startRecording()) return;
        liveView.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        measuring = true;
        measurementEndsAt = SystemClock.elapsedRealtime() + MEASUREMENT_DURATION_MS;
        measureButton.setEnabled(false);
        resetButton.setEnabled(false);
        mainHandler.removeCallbacks(measurementTicker);
        mainHandler.post(measurementTicker);
    }

    private void finishMeasurement() {
        if (!measuring) return;
        measuring = false;
        mainHandler.removeCallbacks(measurementTicker);
        resetButton.setEnabled(true);

        StringBuilder insufficient = new StringBuilder();
        for (int face = 0; face < FACE_COUNT; face++) {
            int valid = measurementSession.getFaceValidLayerCount(face);
            if (valid < 12) {
                if (insufficient.length() > 0) insufficient.append('、');
                insufficient.append(MeasurementSession.FACE_NAMES[face]).append(valid).append("层");
            }
        }
        if (insufficient.length() > 0) {
            stopRecording();
            measureButton.setEnabled(true);
            measureButton.setText("重新开始四面测量（10秒）");
            recordStatus.setText("数据不足：" + insufficient + "；每面至少需要12层有效数据");
            Toast.makeText(this, "部分方向数据不足，请检查USB映射和传感器后重测", Toast.LENGTH_LONG).show();
            return;
        }

        MeasurementSession.Result result = measurementSession.calculate();
        appendMeasurementSummary(result);
        stopRecording();
        showMeasurementResult(result);
        measureButton.setText("再次测量（10秒）");
        measureButton.setEnabled(true);
    }

    private void resetMeasurement() {
        if (measuring) {
            Toast.makeText(this, "当前10秒测量尚未结束", Toast.LENGTH_SHORT).show();
            return;
        }
        liveView.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        measureButton.setEnabled(true);
        resetButton.setEnabled(true);
        measureButton.setText("开始四面测量（10秒）");
        recordStatus.setText("64路实时显示中，可开始同步测量");
    }

    private void showMeasurementResult(MeasurementSession.Result result) {
        contourView.setResult(result);
        liveView.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);
        if (result.hasMeasurement()) {
            waistResultView.setText(String.format(Locale.CHINA, "腰围 %.1f cm", result.waistCm()));
            hipResultView.setText(String.format(Locale.CHINA, "臀围 %.1f cm", result.hipCm()));
            ratioResultView.setText(String.format(Locale.CHINA, "腰臀比 %.2f", result.waistHipRatio()));
        } else {
            waistResultView.setText("腰围 -- cm");
            hipResultView.setText("臀围 -- cm");
            ratioResultView.setText("腰臀比 --");
        }
        String quality = "有效轮廓：" + result.validLayers + "/16层";
        if (result.waistLayer >= 0) quality += "\n腰部：第" + (result.waistLayer + 1) + "层 / " +
                Math.round(result.heightsMm[result.waistLayer]) + " mm";
        if (result.hipLayer >= 0) quality += "\n臀部：第" + (result.hipLayer + 1) + "层 / " +
                Math.round(result.heightsMm[result.hipLayer]) + " mm";
        for (int face = 0; face < FACE_COUNT; face++) {
            quality += "\n" + MeasurementSession.FACE_NAMES[face] + "：" +
                    measurementSession.getFaceSampleCount(face) + "条";
        }
        if (result.warning != null) quality += "\n提示：" + result.warning;
        qualityResultView.setText(quality);
        recordStatus.setText(result.summary() + " · 原始及汇总数据已保存");
    }

    private boolean startRecording() {
        if (recording) return true;
        try {
            String filename = "四面64路腰臀围测量_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/串口距离记录");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            recordUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (recordUri == null) throw new IOException("不能创建记录文件");
            OutputStream stream = resolver.openOutputStream(recordUri, "w");
            if (stream == null) throw new IOException("不能打开记录文件");
            recordWriter = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
            recordWriter.write('\uFEFF');
            recordWriter.write("\"时间\",\"序号\",\"方向\",\"层号\",\"逻辑板\",\"通道\",\"距离_mm\",\"原始数据\"\n");
            recordSequence.set(0);
            recordCount = 0;
            recording = true;
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "开始记录失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            abandonRecord();
            return false;
        }
    }

    private void writeRecord(int face, int layer, int board, int port, String raw, Double distanceMm) {
        synchronized (recordLock) {
            if (!recording || recordWriter == null) return;
            try {
                long sequence = recordSequence.incrementAndGet();
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String row = csv(timestamp) + ',' + sequence + ',' +
                        csv(MeasurementSession.FACE_NAMES[face]) + ',' + (layer + 1) + ',' +
                        csv("板" + (board + 1)) + ',' + csv(String.valueOf(CHANNEL_NAMES[port])) + ',' +
                        (distanceMm == null ? "" : distanceMm) + ',' + csv(raw) + '\n';
                recordWriter.write(row);
                recordCount++;
                if (recordCount % 100 == 0) recordWriter.flush();
            } catch (IOException error) {
                recording = false;
                mainHandler.post(() -> Toast.makeText(this,
                        "记录写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    private void appendMeasurementSummary(MeasurementSession.Result result) {
        synchronized (recordLock) {
            if (recordWriter == null) return;
            try {
                recordWriter.write("\n\"汇总层号\",\"高度_mm\",\"正面中位数_mm\",\"左侧中位数_mm\",\"背面中位数_mm\",\"右侧中位数_mm\",\"左右径_mm\",\"前后径_mm\",\"椭圆周长_mm\"\n");
                for (int layer = 0; layer < LAYERS_PER_FACE; layer++) {
                    recordWriter.write((layer + 1) + "," + result.heightsMm[layer] + "," +
                            number(result.medianDistancesMm[MeasurementSession.FACE_FRONT][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.FACE_LEFT][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.FACE_BACK][layer]) + "," +
                            number(result.medianDistancesMm[MeasurementSession.FACE_RIGHT][layer]) + "," +
                            number(result.widthsMm[layer]) + "," + number(result.depthsMm[layer]) + "," +
                            number(result.circumferencesMm[layer]) + "\n");
                }
                recordWriter.write("\n\"计算结果\",\"腰围_cm\",\"臀围_cm\",\"腰臀比\"\n");
                recordWriter.write("\"result\"," + number(result.waistCm()) + "," +
                        number(result.hipCm()) + "," + number(result.waistHipRatio()) + "\n");
                recordWriter.flush();
            } catch (IOException error) {
                Toast.makeText(this, "汇总写入失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopRecording() {
        Uri completedUri;
        synchronized (recordLock) {
            if (!recording && recordWriter == null) return;
            recording = false;
            try {
                if (recordWriter != null) {
                    recordWriter.flush();
                    recordWriter.close();
                }
            } catch (IOException ignored) {
            }
            completedUri = recordUri;
            recordWriter = null;
            recordUri = null;
        }
        if (completedUri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(completedUri, done, null, null);
        }
        recordStatus.setText(String.format(Locale.CHINA,
                "数据已保存 · %,d条 · 下载/串口距离记录", recordCount));
    }

    private void abandonRecord() {
        synchronized (recordLock) {
            recording = false;
            try {
                if (recordWriter != null) recordWriter.close();
            } catch (IOException ignored) {
            }
            if (recordUri != null) getContentResolver().delete(recordUri, null, null);
            recordWriter = null;
            recordUri = null;
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "";
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void handleDetach(UsbDevice detached) {
        boolean removed = false;
        synchronized (openedDevices) {
            for (int i = openedDevices.size() - 1; i >= 0; i--) {
                Ch348Device opened = openedDevices.get(i);
                if (opened.getUsbDevice().getDeviceId() == detached.getDeviceId()) {
                    opened.close();
                    openedDevices.remove(i);
                    removed = true;
                }
            }
        }
        permissionRequestInFlight = false;
        if (removed || isSupportedDevice(detached)) {
            setOverallStatus("CH348已断开，等待设备恢复", false);
            mainHandler.postDelayed(this::scanAndConnect, 1000);
        }
    }

    private void closeOpenedDevices() {
        synchronized (openedDevices) {
            for (Ch348Device device : openedDevices) device.close();
            openedDevices.clear();
        }
    }

    private void markAllWaiting(String message) {
        for (TextView state : stateViews) if (state != null) state.setText(message);
    }

    private void setOverallStatus(String value, boolean positive) {
        runOnUiThread(() -> {
            overallStatus.setText(value);
            overallStatus.setBackground(roundRect(
                    positive ? Color.rgb(20, 111, 85) : Color.rgb(174, 67, 61), 18));
        });
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String title, int color) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(roundRect(color, 8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(0, 0, dp(9), 0);
        button.setLayoutParams(params);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        measuring = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (recording || recordWriter != null) stopRecording();
        closeOpenedDevices();
        unregisterReceiver(usbReceiver);
        usbExecutor.shutdownNow();
        super.onDestroy();
    }
}
