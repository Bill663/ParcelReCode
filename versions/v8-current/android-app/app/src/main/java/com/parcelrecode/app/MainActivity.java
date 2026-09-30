package com.parcelrecode.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.sourceforge.opencamera.PreferenceKeys;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "ParcelReLabel";
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_PICK = 1002;
    private static final int REQUEST_STORAGE_PERMISSION = 1003;
    private static final int REQUEST_CAMERA_PERMISSION = 1004;
    private static final String EMBEDDED_OPEN_CAMERA_ACTIVITY = "net.sourceforge.opencamera.MainActivity";
    private static final long FLASH_CAPTURE_EXPOSURE_TIME_NS = 1_000_000_000L / 250L;
    private static final String FLASH_CAPTURE_ISO = "6400";
    private static final String LANGUAGE_PREFERENCES = "language_preferences";
    private static final String LANGUAGE_KEY = "language";
    private static final String APP_PREFERENCES = "parcel_recode_preferences";
    private static final String LABEL_LAYOUT_KEY = "label_layout";
    private static final String ACCESSIBILITY_MODE_KEY = "accessibility_mode";
    private static final String STATE_PENDING_CAMERA_URI = "pending_camera_uri";
    private static final String STATE_PENDING_SCAN_TARGET = "pending_scan_target";
    private static final int QR_BITMAP_SIZE = 600;
    private static final long QR_UPDATE_DEBOUNCE_MS = 120L;
    private static final String DEBUG_PHOTO_PATH_EXTRA = "com.parcelrecode.app.DEBUG_PHOTO_PATH";
    private static final String DEBUG_PHOTO_SLOT_EXTRA = "com.parcelrecode.app.DEBUG_PHOTO_SLOT";
    private static final String DEBUG_LABEL_LAYOUT_EXTRA = "com.parcelrecode.app.DEBUG_LABEL_LAYOUT";
    private static final int LABEL_LAYOUT_SINGLE_2X2 = 0;
    private static final int LABEL_LAYOUT_DUAL_4X6 = 1;
    private static final int SCAN_TARGET_SINGLE = 0;
    private static final int SCAN_TARGET_4X6_TOP = 1;
    private static final int SCAN_TARGET_4X6_BOTTOM = 2;

    private final ScanSessionController scanSession = new ScanSessionController();
    private final ParcelPrintController parcelPrintController = new ParcelPrintController();
    private ParcelScanController parcelScanController;

    private Button takePhotoButton;
    private Button choosePhotoButton;
    private ImageButton settingsButton;
    private Button printButton;
    private Button clearAllDataButton;
    private Button takeTopPhotoButton;
    private Button takeBottomPhotoButton;
    private Button editTopTnoButton;
    private Button editBottomTnoButton;
    private ImageView photoPreview;
    private ImageView topSlotPhotoPreview;
    private ImageView bottomSlotPhotoPreview;
    private ImageView qrPreview;
    private TextView statusText;
    private TextView carrierText;
    private TextView qrPayloadText;
    private TextView fourBySixStepText;
    private TextView topSlotPayloadText;
    private TextView bottomSlotPayloadText;
    private EditText trackingInput;
    private EditText fedExMachineInput;
    private EditText zipInput;
    private LinearLayout trackingInputGroup;
    private LinearLayout fedExMachineGroup;
    private LinearLayout uspsZipGroup;
    private LinearLayout bottomQrGroup;
    private LinearLayout singleScanActions;
    private LinearLayout singleModeGroup;
    private Spinner carrierSpinner;
    private TextView modeBadge;

    private Uri pendingCameraUri;
    private TrackingRules.Candidate currentCandidate;
    private Bitmap currentQrBitmap;
    private String currentPayload;
    private Bitmap topSlotPhotoBitmap;
    private Bitmap bottomSlotPhotoBitmap;
    private final FourBySixWorkflow<Bitmap> fourBySixWorkflow = new FourBySixWorkflow<>();
    private int pendingScanTarget = SCAN_TARGET_SINGLE;
    private int currentLabelLayout;
    private boolean accessibilityModeEnabled;
    private boolean updatingFields;
    private boolean destroyed;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingQrUpdate = () -> {
        if (destroyed) return;
        updateQrFromFields();
        refreshActive4x6SlotFromCurrent();
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        String language = preferredLanguage(newBase);
        Locale locale = "zh".equals(language) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(newBase.getResources().getConfiguration());
        configuration.setLocale(locale);
        ContextThemeWrapper localizedContext = new ContextThemeWrapper(newBase, R.style.ParcelAppTheme);
        localizedContext.applyOverrideConfiguration(configuration);
        super.attachBaseContext(localizedContext);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.parcel_activity_main);
        bindViews();
        parcelScanController = new ParcelScanController(this);
        wireEvents();
        restoreCameraState(savedInstanceState);
        handleDebugPhotoIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugPhotoIntent(intent);
    }

    private void restoreCameraState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String pendingUri = savedInstanceState.getString(STATE_PENDING_CAMERA_URI, "");
        if (!pendingUri.isEmpty()) pendingCameraUri = Uri.parse(pendingUri);
        pendingScanTarget = normalizedScanTarget(
                savedInstanceState.getInt(STATE_PENDING_SCAN_TARGET, SCAN_TARGET_SINGLE));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (pendingCameraUri != null) {
            outState.putString(STATE_PENDING_CAMERA_URI, pendingCameraUri.toString());
        }
        outState.putInt(STATE_PENDING_SCAN_TARGET, pendingScanTarget);
        super.onSaveInstanceState(outState);
    }

    private void bindViews() {
        takePhotoButton = findViewById(R.id.takePhotoButton);
        choosePhotoButton = findViewById(R.id.choosePhotoButton);
        settingsButton = findViewById(R.id.settingsButton);
        printButton = findViewById(R.id.printButton);
        clearAllDataButton = findViewById(R.id.clearAllDataButton);
        takeTopPhotoButton = findViewById(R.id.takeTopPhotoButton);
        takeBottomPhotoButton = findViewById(R.id.takeBottomPhotoButton);
        editTopTnoButton = findViewById(R.id.editTopTnoButton);
        editBottomTnoButton = findViewById(R.id.editBottomTnoButton);
        photoPreview = findViewById(R.id.photoPreview);
        topSlotPhotoPreview = findViewById(R.id.topSlotPhotoPreview);
        bottomSlotPhotoPreview = findViewById(R.id.bottomSlotPhotoPreview);
        qrPreview = findViewById(R.id.qrPreview);
        statusText = findViewById(R.id.statusText);
        carrierText = findViewById(R.id.carrierText);
        qrPayloadText = findViewById(R.id.qrPayloadText);
        fourBySixStepText = findViewById(R.id.fourBySixStepText);
        topSlotPayloadText = findViewById(R.id.topSlotPayloadText);
        bottomSlotPayloadText = findViewById(R.id.bottomSlotPayloadText);
        trackingInput = findViewById(R.id.trackingInput);
        fedExMachineInput = findViewById(R.id.fedExMachineInput);
        zipInput = findViewById(R.id.zipInput);
        trackingInputGroup = findViewById(R.id.trackingInputGroup);
        fedExMachineGroup = findViewById(R.id.fedExMachineGroup);
        uspsZipGroup = findViewById(R.id.uspsZipGroup);
        bottomQrGroup = findViewById(R.id.bottomQrGroup);
        singleScanActions = findViewById(R.id.singleScanActions);
        singleModeGroup = findViewById(R.id.singleModeGroup);
        carrierSpinner = findViewById(R.id.carrierSpinner);
        modeBadge = findViewById(R.id.modeBadge);
    }

    private static String preferredLanguage(Context context) {
        String saved = context.getSharedPreferences(LANGUAGE_PREFERENCES, MODE_PRIVATE)
                .getString(LANGUAGE_KEY, "");
        if (!saved.isEmpty()) return saved;
        return "zh".equals(Locale.getDefault().getLanguage()) ? "zh" : "en";
    }

    private void wireEvents() {
        takePhotoButton.setOnClickListener(view -> launchCamera(SCAN_TARGET_SINGLE));
        choosePhotoButton.setOnClickListener(view -> launchPicker());
        settingsButton.setOnClickListener(view -> showSettingsDialog());
        printButton.setOnClickListener(view -> printLabel());
        clearAllDataButton.setOnClickListener(view -> clearAllData());
        takeTopPhotoButton.setOnClickListener(view -> launchCamera(SCAN_TARGET_4X6_TOP));
        takeBottomPhotoButton.setOnClickListener(view -> launchCamera(SCAN_TARGET_4X6_BOTTOM));
        editTopTnoButton.setOnClickListener(view -> edit4x6Slot(true));
        editBottomTnoButton.setOnClickListener(view -> edit4x6Slot(false));
        currentLabelLayout = savedLabelLayout();
        accessibilityModeEnabled = savedAccessibilityMode();

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!updatingFields) onManualQrFieldsChanged();
            }
            @Override public void afterTextChanged(Editable editable) {}
        };
        trackingInput.addTextChangedListener(watcher);
        fedExMachineInput.addTextChangedListener(watcher);
        zipInput.addTextChangedListener(watcher);
        trackingInput.setOnClickListener(view -> showFallbackKeyboardIfNeeded(trackingInput, false));
        fedExMachineInput.setOnClickListener(view -> showFallbackKeyboardIfNeeded(fedExMachineInput, false));
        zipInput.setOnClickListener(view -> showFallbackKeyboardIfNeeded(zipInput, true));
        trackingInput.setOnTouchListener((view, event) ->
                openFallbackKeyboardOnTouch(view, event));
        fedExMachineInput.setOnTouchListener((view, event) ->
                openFallbackKeyboardOnTouch(view, event));
        zipInput.setOnTouchListener((view, event) ->
                openFallbackKeyboardOnTouch(view, event));
        carrierSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingFields) onManualQrFieldsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        updatePrintLayoutMode();
        applyAccessibilityMode();
        setStatusNeutral(isDual4x6Selected()
                ? getString(R.string.four_by_six_scan_first)
                : getString(R.string.ready_for_photo));
    }

    private int savedLabelLayout() {
        int saved = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
                .getInt(LABEL_LAYOUT_KEY, LABEL_LAYOUT_SINGLE_2X2);
        return saved == LABEL_LAYOUT_DUAL_4X6 ? LABEL_LAYOUT_DUAL_4X6 : LABEL_LAYOUT_SINGLE_2X2;
    }

    private void saveLabelLayout(int position) {
        int layout = position == LABEL_LAYOUT_DUAL_4X6
                ? LABEL_LAYOUT_DUAL_4X6
                : LABEL_LAYOUT_SINGLE_2X2;
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putInt(LABEL_LAYOUT_KEY, layout)
                .apply();
    }

    private void setLabelLayout(int position, boolean updateStatus) {
        mainHandler.removeCallbacks(pendingQrUpdate);
        int nextLayout = position == LABEL_LAYOUT_DUAL_4X6
                ? LABEL_LAYOUT_DUAL_4X6
                : LABEL_LAYOUT_SINGLE_2X2;
        if (currentLabelLayout == nextLayout) {
            updatePrintLayoutMode();
            return;
        }

        currentLabelLayout = nextLayout;
        saveLabelLayout(currentLabelLayout);
        fourBySixWorkflow.clear();
        updatePrintLayoutMode();
        if (!updatingFields) updateQrFromFields();
        if (!updateStatus) return;
        setStatusNeutral(isDual4x6Selected()
                ? getString(R.string.four_by_six_scan_first)
                : getString(R.string.ready_for_photo));
    }

    private boolean savedAccessibilityMode() {
        return getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
                .getBoolean(ACCESSIBILITY_MODE_KEY, false);
    }

    private void saveAccessibilityMode(boolean enabled) {
        getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(ACCESSIBILITY_MODE_KEY, enabled)
                .apply();
    }

    private void showSettingsDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        root.setPadding(padding, dp(8), padding, 0);

        TextView languageLabel = new TextView(this);
        languageLabel.setText(R.string.language_prompt);
        languageLabel.setTextColor(getColor(R.color.muted));
        languageLabel.setTextSize(14);
        root.addView(languageLabel);

        Spinner languageSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> languageAdapter = ArrayAdapter.createFromResource(
                this,
                R.array.language_options,
                android.R.layout.simple_spinner_item);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        languageSpinner.setSelection("zh".equals(preferredLanguage(this)) ? 1 : 0, false);
        root.addView(languageSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)));

        TextView layoutLabel = new TextView(this);
        layoutLabel.setText(R.string.label_layout);
        layoutLabel.setTextColor(getColor(R.color.muted));
        layoutLabel.setTextSize(14);
        LinearLayout.LayoutParams layoutLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layoutLabelParams.topMargin = dp(12);
        root.addView(layoutLabel, layoutLabelParams);

        Spinner layoutSpinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.label_layout_options,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        layoutSpinner.setAdapter(adapter);
        layoutSpinner.setSelection(currentLabelLayout, false);
        root.addView(layoutSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)));

        Button largeTextButton = new Button(this);
        largeTextButton.setAllCaps(false);
        largeTextButton.setText(accessibilityModeEnabled
                ? R.string.accessibility_mode_on
                : R.string.accessibility_mode_off);
        largeTextButton.setBackgroundResource(R.drawable.secondary_button_background);
        largeTextButton.setTextColor(getColor(R.color.ink));
        largeTextButton.setElevation(0f);
        LinearLayout.LayoutParams largeTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52));
        largeTextParams.topMargin = dp(12);
        root.addView(largeTextButton, largeTextParams);

        TextView versionText = new TextView(this);
        versionText.setText(R.string.version_label);
        versionText.setTextColor(getColor(R.color.muted));
        versionText.setTextSize(12);
        versionText.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        versionParams.topMargin = dp(16);
        root.addView(versionText, versionParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(root)
                .setPositiveButton(R.string.done, null)
                .create();

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLanguage = position == 1 ? "zh" : "en";
                if (selectedLanguage.equals(preferredLanguage(MainActivity.this))) return;
                getSharedPreferences(LANGUAGE_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putString(LANGUAGE_KEY, selectedLanguage)
                        .apply();
                dialog.dismiss();
                recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        layoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setLabelLayout(position, true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        largeTextButton.setOnClickListener(view -> {
            accessibilityModeEnabled = !accessibilityModeEnabled;
            saveAccessibilityMode(accessibilityModeEnabled);
            largeTextButton.setText(accessibilityModeEnabled
                    ? R.string.accessibility_mode_on
                    : R.string.accessibility_mode_off);
            applyAccessibilityMode();
        });

        dialog.show();
    }

    private void applyAccessibilityMode() {
        carrierText.setTextSize(accessibilityModeEnabled ? 24 : 21);
        trackingInput.setTextSize(accessibilityModeEnabled ? 21 : 18);
        trackingInput.setMinHeight(dp(accessibilityModeEnabled ? 78 : 52));
        fedExMachineInput.setTextSize(accessibilityModeEnabled ? 22 : 17);
        fedExMachineInput.setMinHeight(dp(accessibilityModeEnabled ? 62 : 52));
        zipInput.setTextSize(accessibilityModeEnabled ? 24 : 17);
        zipInput.setMinHeight(dp(accessibilityModeEnabled ? 58 : 48));
        qrPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        fourBySixStepText.setTextSize(accessibilityModeEnabled ? 18 : 14);
        topSlotPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        bottomSlotPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        statusText.setTextSize(accessibilityModeEnabled ? 17 : 15);
        modeBadge.setTextSize(accessibilityModeEnabled ? 14 : 12);
    }

    private boolean openFallbackKeyboardOnTouch(View view, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP || hasEnabledInputMethod()) {
            return false;
        }
        view.performClick();
        return true;
    }

    private void showFallbackKeyboardIfNeeded(EditText target, boolean numbersOnly) {
        if (hasEnabledInputMethod()) return;
        showFallbackKeyboard(target, numbersOnly);
    }

    private boolean hasEnabledInputMethod() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        return inputMethodManager != null
                && !inputMethodManager.getEnabledInputMethodList().isEmpty();
    }

    private void showFallbackKeyboard(EditText target, boolean numbersOnly) {
        StringBuilder value = new StringBuilder(target.getText().toString());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, dp(8), padding, 0);

        TextView display = new TextView(this);
        display.setBackgroundResource(R.drawable.soft_background);
        display.setGravity(Gravity.CENTER_VERTICAL);
        display.setMinHeight(dp(56));
        display.setPadding(dp(12), dp(8), dp(12), dp(8));
        display.setText(value);
        display.setTextColor(getColor(R.color.ink));
        display.setTextSize(20);
        display.setTypeface(display.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(display, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        keyboardParams.topMargin = dp(10);
        root.addView(keyboard, keyboardParams);

        String[][] rows = numbersOnly
                ? new String[][]{
                    {"1", "2", "3"},
                    {"4", "5", "6"},
                    {"7", "8", "9"},
                    {"0", "DEL"}
                }
                : new String[][]{
                    {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                    {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"},
                    {"A", "S", "D", "F", "G", "H", "J", "K", "L"},
                    {"Z", "X", "C", "V", "B", "N", "M", "DEL"}
                };

        for (String[] rowKeys : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            keyboard.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)));
            for (String key : rowKeys) {
                Button button = createKeyboardButton(key);
                if ("DEL".equals(key)) {
                    button.setContentDescription(getString(R.string.keyboard_delete));
                }
                button.setOnClickListener(view -> {
                    if ("DEL".equals(key)) {
                        if (value.length() > 0) value.deleteCharAt(value.length() - 1);
                    } else if ((!numbersOnly && value.length() < 40)
                            || (numbersOnly && value.length() < 5)) {
                        value.append(key);
                    }
                    display.setText(value);
                });
                row.addView(button);
            }
        }

        Button clearButton = new Button(this);
        clearButton.setText(R.string.keyboard_clear);
        clearButton.setAllCaps(false);
        clearButton.setOnClickListener(view -> {
            value.setLength(0);
            display.setText(value);
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48));
        clearParams.topMargin = dp(4);
        root.addView(clearButton, clearParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(fallbackKeyboardTitle(target, numbersOnly))
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.done, (unusedDialog, unusedButton) -> {
                    target.setText(value.toString());
                    target.setSelection(target.length());
                    target.requestFocus();
                })
                .create();
        dialog.setOnShowListener(unused -> dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
        dialog.show();
    }

    private int fallbackKeyboardTitle(EditText target, boolean numbersOnly) {
        if (target == fedExMachineInput) return R.string.edit_processed_tno;
        return numbersOnly ? R.string.edit_zip_code : R.string.edit_tracking_number;
    }

    private Button createKeyboardButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(1), dp(1), dp(1), dp(1));
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void launchCamera(int target) {
        pendingScanTarget = normalizedScanTarget(target);
        prepareForNewScanRequest(pendingScanTarget);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "parcel-" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ParcelReLabel");
        }
        pendingCameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (pendingCameraUri == null) {
            showError(getString(R.string.could_not_prepare_camera));
            return;
        }

        try {
            startActivityForResult(createCameraIntent(), REQUEST_CAPTURE);
        } catch (Exception error) {
            Log.e(LOG_TAG, "Embedded Open Camera failed to launch", error);
            getContentResolver().delete(pendingCameraUri, null, null);
            pendingCameraUri = null;
            showError(getString(R.string.could_not_prepare_camera));
        }
    }

    private Intent createCameraIntent() {
        seedEmbeddedOpenCameraPreferences();
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.setClassName(getPackageName(), EMBEDDED_OPEN_CAMERA_ACTIVITY);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private void seedEmbeddedOpenCameraPreferences() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(PreferenceKeys.FirstTimePreferenceKey, true)
                .putBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, false)
                .putInt(PreferenceKeys.LatestVersionPreferenceKey, Integer.MAX_VALUE)
                .putString(PreferenceKeys.CameraAPIPreferenceKey, "preference_camera_api_camera2")
                .putString(PreferenceKeys.getFlashPreferenceKey(0), "flash_on")
                .putString(PreferenceKeys.ExposurePreferenceKey, "0")
                .putString(PreferenceKeys.ISOPreferenceKey, FLASH_CAPTURE_ISO)
                .putLong(PreferenceKeys.ExposureTimePreferenceKey, FLASH_CAPTURE_EXPOSURE_TIME_NS)
                .putBoolean(PreferenceKeys.Camera2FakeFlashPreferenceKey, false)
                .apply();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            if (requestCode == REQUEST_CAMERA_PERMISSION) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    launchCamera(pendingScanTarget);
                } else {
                    showError(getString(R.string.camera_permission_required));
                }
            }
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera(pendingScanTarget);
        } else {
            showError(getString(R.string.storage_permission_required));
        }
    }

    private void launchPicker() {
        pendingScanTarget = SCAN_TARGET_SINGLE;
        prepareForNewScanRequest(pendingScanTarget);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK);
    }

    private void handleDebugPhotoIntent(Intent intent) {
        if (intent == null || !isDebuggable()) return;
        applyDebugLabelLayout(intent);
        String path = intent.getStringExtra(DEBUG_PHOTO_PATH_EXTRA);
        if (path == null || path.trim().isEmpty()) return;
        int target = debugScanTarget(intent);
        prepareForNewScanRequest(target);
        processPhoto(Uri.fromFile(new File(path)), target);
    }

    private void applyDebugLabelLayout(Intent intent) {
        String layout = intent.getStringExtra(DEBUG_LABEL_LAYOUT_EXTRA);
        if ("single".equalsIgnoreCase(layout)) {
            currentLabelLayout = LABEL_LAYOUT_SINGLE_2X2;
            updatePrintLayoutMode();
        } else if ("4x6".equalsIgnoreCase(layout)) {
            currentLabelLayout = LABEL_LAYOUT_DUAL_4X6;
            updatePrintLayoutMode();
        }
    }

    private int debugScanTarget(Intent intent) {
        String slot = intent.getStringExtra(DEBUG_PHOTO_SLOT_EXTRA);
        if ("top".equalsIgnoreCase(slot)) return SCAN_TARGET_4X6_TOP;
        if ("bottom".equalsIgnoreCase(slot)) return SCAN_TARGET_4X6_BOTTOM;
        if (isDual4x6Selected()) {
            int nextSlot = fourBySixWorkflow.nextSlotNumber();
            if (nextSlot == 1) return SCAN_TARGET_4X6_TOP;
            if (nextSlot == 2) return SCAN_TARGET_4X6_BOTTOM;
        }
        return SCAN_TARGET_SINGLE;
    }

    private int normalizedScanTarget(int target) {
        if (!isDual4x6Selected()) return SCAN_TARGET_SINGLE;
        if (target == SCAN_TARGET_4X6_TOP || target == SCAN_TARGET_4X6_BOTTOM) return target;
        return SCAN_TARGET_SINGLE;
    }

    private void prepareForNewScanRequest(int target) {
        if (!isDual4x6Selected() || !fourBySixWorkflow.prepareForNewScan()) return;
        clearCurrentScanState();
        sync4x6Panel();
        setStatusNeutral(getString(R.string.four_by_six_scan_first));
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE && requestCode != REQUEST_PICK) return;
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_CAPTURE && pendingCameraUri != null) {
                getContentResolver().delete(pendingCameraUri, null, null);
                pendingCameraUri = null;
            }
            return;
        }

        Uri imageUri = requestCode == REQUEST_CAPTURE
                ? pendingCameraUri
                : data == null ? null : data.getData();
        int target = requestCode == REQUEST_CAPTURE ? pendingScanTarget : SCAN_TARGET_SINGLE;
        if (requestCode == REQUEST_CAPTURE) pendingCameraUri = null;
        if (imageUri != null) processPhoto(imageUri, target);
    }

    private void processPhoto(Uri uri, int target) {
        int sessionId = scanSession.beginScan();
        setBusy(true, getString(R.string.reading_parcel_label));
        clearCurrentScanState();
        parcelScanController.processPhoto(uri, target, sessionId, new ParcelScanController.Callback() {
            @Override
            public void onScanComplete(ParcelScanController.ScanResult result) {
                runOnUiThread(() -> {
                    if (!isActivityUsable()) return;
                    if (!scanSession.isCurrent(result.sessionId)) return;
                    setScanPreviewBitmap(result.bitmap, result.target);
                    applyCandidate(result.candidate, result.target);
                });
            }

            @Override
            public void onScanError(ParcelScanController.ScanError error) {
                runOnUiThread(() -> {
                    if (!isActivityUsable()) return;
                    if (!scanSession.isCurrent(error.sessionId)) return;
                    showError(getString(R.string.could_not_read_photo));
                });
            }
        });
    }

    private void setScanPreviewBitmap(Bitmap bitmap, int target) {
        if (target == SCAN_TARGET_4X6_TOP) {
            topSlotPhotoBitmap = bitmap;
            topSlotPhotoPreview.setImageBitmap(bitmap);
            return;
        }
        if (target == SCAN_TARGET_4X6_BOTTOM) {
            bottomSlotPhotoBitmap = bitmap;
            bottomSlotPhotoPreview.setImageBitmap(bitmap);
            return;
        }
        photoPreview.setImageBitmap(bitmap);
    }

    private void applyCandidate(TrackingRules.Candidate candidate, int target) {
        setBusy(false, "");
        if (candidate == null) {
            clearCurrentScanState();
            showError(getString(R.string.no_tracking_found));
            return;
        }

        currentCandidate = candidate;
        updatingFields = true;
        carrierText.setText(candidate.carrier);
        carrierSpinner.setSelection(positionForCompany(candidate.company), false);
        trackingInput.setText(candidate.tracking);
        fedExMachineInput.setText(candidate.machineValue);
        zipInput.setText(candidate.zipCode);
        updatingFields = false;
        updateQrFromFields();
        if (isDual4x6Selected()) {
            acceptCurrentQrFor4x6(target, false);
        } else {
            setStatusSuccess(getString(R.string.detected_confirm_message, candidate.carrier));
        }
    }

    private void updateQrFromFields() {
        String tracking = TrackingRules.clean(trackingInput.getText().toString());
        String selectedCompany = selectedCarrierCompany();
        String company = selectedCompany.isEmpty()
                ? TrackingRules.companyForTracking(tracking)
                : selectedCompany;
        String zip = zipInput.getText().toString();
        fedExMachineGroup.setVisibility("fedex".equals(company) ? View.VISIBLE : View.GONE);
        uspsZipGroup.setVisibility("usps".equals(company) ? View.VISIBLE : View.GONE);

        String machine = "fedex".equals(company)
                ? fedExMachineInput.getText().toString()
                : "";
        if ("fedex".equals(company)) {
            String cleanMachine = TrackingRules.clean(machine);
            if (trackingInput.hasFocus()) {
                if (tracking.matches("\\d{12}")) {
                    String syncedMachine = TrackingRules.fedExMachineWithTracking(cleanMachine, tracking);
                    if (!syncedMachine.equals(cleanMachine)) {
                        updatingFields = true;
                        fedExMachineInput.setText(syncedMachine);
                        fedExMachineInput.setSelection(fedExMachineInput.length());
                        updatingFields = false;
                        machine = syncedMachine;
                    }
                } else {
                    machine = "";
                }
            } else {
                String trackingFromMachine = TrackingRules.fedExTrackingFromMachine(cleanMachine);
                if (!trackingFromMachine.isEmpty()) {
                    tracking = trackingFromMachine;
                    if (!tracking.equals(TrackingRules.clean(trackingInput.getText().toString()))) {
                        updatingFields = true;
                        trackingInput.setText(tracking);
                        trackingInput.setSelection(trackingInput.length());
                        updatingFields = false;
                    }
                } else if (!tracking.matches("\\d{12}")) {
                    tracking = "";
                }
            }
        }
        trackingInputGroup.setVisibility(View.VISIBLE);
        currentPayload = TrackingRules.formatQrPayload(tracking, zip, machine, selectedCompany);
        if (currentPayload == null && "fedex".equals(company) && machine.isEmpty()) {
            String fallback = TrackingRules.formatQrPayload(tracking, zip, "", selectedCompany);
            if (fallback != null) {
                currentPayload = fallback;
                if (fedExMachineInput.getText().length() == 0) {
                    updatingFields = true;
                    fedExMachineInput.setText(fallback);
                    fedExMachineInput.setSelection(fedExMachineInput.length());
                    updatingFields = false;
                    machine = fallback;
                }
            }
        }
        if (currentPayload != null
                && "fedex".equals(company)
                && machine.isEmpty()
                && tracking.matches("\\d{12}")
                && fedExMachineInput.getText().length() == 0) {
            updatingFields = true;
            fedExMachineInput.setText(currentPayload);
            fedExMachineInput.setSelection(fedExMachineInput.length());
            updatingFields = false;
        }
        if (currentPayload == null) {
            currentQrBitmap = null;
            qrPreview.setImageDrawable(null);
            qrPreview.setVisibility(View.GONE);
            qrPayloadText.setText("usps".equals(company)
                    ? R.string.enter_destination_zip
                    : R.string.waiting_for_valid_tracking);
            updatePrintButtonState();
            return;
        }

        try {
            currentQrBitmap = createQrBitmap(currentPayload, QR_BITMAP_SIZE);
            qrPreview.setImageBitmap(currentQrBitmap);
            qrPreview.setVisibility(View.VISIBLE);
            qrPayloadText.setText(currentPayload);
            updatePrintButtonState();
        } catch (Exception error) {
            currentQrBitmap = null;
            qrPreview.setImageDrawable(null);
            qrPreview.setVisibility(View.GONE);
            updatePrintButtonState();
            showError(getString(R.string.could_not_generate_qr));
        }
    }

    private boolean isDual4x6Selected() {
        return currentLabelLayout == LABEL_LAYOUT_DUAL_4X6;
    }

    private void updatePrintLayoutMode() {
        boolean dual = isDual4x6Selected();
        singleScanActions.setVisibility(dual ? View.GONE : View.VISIBLE);
        singleModeGroup.setVisibility(dual ? View.GONE : View.VISIBLE);
        if (bottomQrGroup != null) {
            bottomQrGroup.setVisibility(dual ? View.VISIBLE : View.GONE);
        }
        modeBadge.setText(dual ? R.string.mode_4x6 : R.string.mode_2x2);
        printButton.setText(dual ? R.string.print_4x6_label : R.string.print_2x2_label);
        sync4x6Panel();
    }

    private void setSlotEditButtonState(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void onManualQrFieldsChanged() {
        mainHandler.removeCallbacks(pendingQrUpdate);
        mainHandler.postDelayed(pendingQrUpdate, QR_UPDATE_DEBOUNCE_MS);
    }

    private void acceptCurrentQrFor4x6(int target, boolean showInvalidStatus) {
        if (!isDual4x6Selected()) return;
        boolean saved = target == SCAN_TARGET_4X6_TOP
                ? fourBySixWorkflow.replaceTop(currentQrBitmap, currentPayload)
                : target == SCAN_TARGET_4X6_BOTTOM
                        && fourBySixWorkflow.replaceBottom(currentQrBitmap, currentPayload);
        sync4x6Panel();
        if (!saved) {
            if (showInvalidStatus) setStatusError(getString(R.string.four_by_six_need_qr));
            return;
        }
        setStatusSuccess(fourBySixStepMessage());
    }

    private void refreshActive4x6SlotFromCurrent() {
        if (!isDual4x6Selected()) return;
        if (fourBySixWorkflow.replaceActive(currentQrBitmap, currentPayload)) {
            sync4x6Panel();
        }
    }

    private void sync4x6Panel() {
        FourBySixWorkflow.Slot<Bitmap> topSlot = fourBySixWorkflow.topSlot();
        FourBySixWorkflow.Slot<Bitmap> bottomSlot = fourBySixWorkflow.bottomSlot();
        if (topSlotPayloadText != null) {
            topSlotPayloadText.setText(topSlot == null ? getString(R.string.empty_qr_slot) : topSlot.payload);
        }
        if (bottomSlotPayloadText != null) {
            bottomSlotPayloadText.setText(bottomSlot == null ? getString(R.string.empty_qr_slot) : bottomSlot.payload);
        }
        if (fourBySixStepText != null) {
            fourBySixStepText.setText(fourBySixStepMessage());
        }
        takeTopPhotoButton.setEnabled(isDual4xSelectedAndIdle());
        takeBottomPhotoButton.setEnabled(isDual4xSelectedAndIdle());
        setSlotEditButtonState(editTopTnoButton, topSlot != null && !scanSession.isScanInProgress());
        setSlotEditButtonState(editBottomTnoButton, bottomSlot != null && !scanSession.isScanInProgress());
        updatePrintButtonState();
    }

    private boolean isDual4xSelectedAndIdle() {
        return isDual4x6Selected() && !scanSession.isScanInProgress();
    }

    private String fourBySixStepMessage() {
        if (fourBySixWorkflow.isReady()) return getString(R.string.four_by_six_ready);
        if (fourBySixWorkflow.topSlot() == null && fourBySixWorkflow.bottomSlot() != null) {
            return getString(R.string.scan_top_label);
        }
        if (fourBySixWorkflow.topSlot() == null) return getString(R.string.four_by_six_scan_first);
        return getString(R.string.scan_second_label);
    }

    private void clearAllData() {
        scanSession.cancelOutstanding();
        setBusy(false, "");
        clearCurrentScanState();
        clear4x6Data();
        photoPreview.setImageDrawable(null);
        pendingCameraUri = null;
        pendingScanTarget = SCAN_TARGET_SINGLE;
        setStatusNeutral(isDual4x6Selected()
                ? getString(R.string.four_by_six_scan_first)
                : getString(R.string.ready_for_photo));
    }

    private void clear4x6Data() {
        fourBySixWorkflow.clear();
        topSlotPhotoBitmap = null;
        bottomSlotPhotoBitmap = null;
        topSlotPhotoPreview.setImageDrawable(null);
        bottomSlotPhotoPreview.setImageDrawable(null);
        sync4x6Panel();
    }

    private void clearCurrentScanState() {
        mainHandler.removeCallbacks(pendingQrUpdate);
        currentCandidate = null;
        currentQrBitmap = null;
        currentPayload = null;
        updatingFields = true;
        carrierText.setText(R.string.not_detected);
        carrierSpinner.setSelection(0, false);
        trackingInput.setText("");
        fedExMachineInput.setText("");
        zipInput.setText("");
        updatingFields = false;
        trackingInputGroup.setVisibility(View.VISIBLE);
        fedExMachineGroup.setVisibility(View.GONE);
        uspsZipGroup.setVisibility(View.GONE);
        qrPayloadText.setText(R.string.waiting_for_tracking);
        qrPreview.setImageDrawable(null);
        qrPreview.setVisibility(View.GONE);
        updatePrintButtonState();
    }

    private void edit4x6Slot(boolean topSlot) {
        String activePayload = topSlot
                ? fourBySixWorkflow.topPayload()
                : fourBySixWorkflow.bottomPayload();
        if (activePayload == null) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setText(activePayload);
        input.setSelectAllOnFocus(true);
        input.setHint(R.string.enter_processed_tno);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(topSlot ? R.string.edit_top_tno : R.string.edit_bottom_tno)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.done, null)
                .create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String editedPayload = TrackingRules.clean(input.getText().toString());
                if (editedPayload.isEmpty()) {
                    input.setError(getString(R.string.enter_processed_tno));
                    return;
                }
                try {
                    Bitmap editedQrBitmap = createQrBitmap(editedPayload, QR_BITMAP_SIZE);
                    if (topSlot) {
                        fourBySixWorkflow.replaceTop(editedQrBitmap, editedPayload);
                    } else {
                        fourBySixWorkflow.replaceBottom(editedQrBitmap, editedPayload);
                    }
                    sync4x6Panel();
                    dialog.dismiss();
                } catch (Exception error) {
                    input.setError(getString(R.string.could_not_generate_qr));
                }
            });
        });
        dialog.show();
    }

    private void updatePrintButtonState() {
        printButton.setText(isDual4x6Selected()
                ? R.string.print_4x6_label
                : R.string.print_2x2_label);
        if (scanSession.isScanInProgress()) {
            printButton.setEnabled(false);
            return;
        }
        if (isDual4x6Selected()) {
            printButton.setEnabled(fourBySixWorkflow.isReady());
            return;
        }
        printButton.setEnabled(currentQrBitmap != null && currentPayload != null);
    }

    private String selectedCarrierCompany() {
        switch (carrierSpinner.getSelectedItemPosition()) {
            case 1:
                return "fedex";
            case 2:
                return "usps";
            case 3:
                return "uniuni";
            case 4:
                return "gofo";
            case 5:
                return "swiftx";
            case 6:
                return "speedx";
            case 7:
                return "ontrac";
            default:
                return "";
        }
    }

    private int positionForCompany(String company) {
        if ("fedex".equals(company)) return 1;
        if ("usps".equals(company)) return 2;
        if ("uniuni".equals(company)) return 3;
        if ("gofo".equals(company)) return 4;
        if ("swiftx".equals(company)) return 5;
        if ("speedx".equals(company)) return 6;
        if ("ontrac".equals(company)) return 7;
        return 0;
    }

    private Bitmap createQrBitmap(String value, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int rowOffset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[rowOffset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private void printLabel() {
        boolean dual4x6 = isDual4x6Selected();
        FourBySixWorkflow.Slot<Bitmap> topSlot = fourBySixWorkflow.topSlot();
        FourBySixWorkflow.Slot<Bitmap> bottomSlot = fourBySixWorkflow.bottomSlot();
        ParcelPrintController.PrintLabel topLabel = dual4x6
                ? topSlot == null ? null : new ParcelPrintController.PrintLabel(topSlot.qr, "", topSlot.payload)
                : new ParcelPrintController.PrintLabel(
                        currentQrBitmap,
                        trackingInput.getText().toString(),
                        currentPayload);
        ParcelPrintController.PrintLabel bottomLabel = dual4x6 && bottomSlot != null
                ? new ParcelPrintController.PrintLabel(bottomSlot.qr, "", bottomSlot.payload)
                : null;
        if (!parcelPrintController.print(this, dual4x6, topLabel, bottomLabel)) return;
        if (dual4x6) {
            fourBySixWorkflow.markPrinted();
            sync4x6Panel();
            setStatusNeutral(getString(R.string.four_by_six_printed_next_scan));
        }
    }

    private String activeCompany() {
        String selectedCompany = selectedCarrierCompany();
        if (!selectedCompany.isEmpty()) return selectedCompany;
        return TrackingRules.companyForTracking(TrackingRules.clean(trackingInput.getText().toString()));
    }

    private void setBusy(boolean busy, String message) {
        scanSession.setScanInProgress(busy);
        takePhotoButton.setEnabled(!busy);
        choosePhotoButton.setEnabled(!busy);
        takeTopPhotoButton.setEnabled(isDual4xSelectedAndIdle());
        takeBottomPhotoButton.setEnabled(isDual4xSelectedAndIdle());
        setSlotEditButtonState(editTopTnoButton, fourBySixWorkflow.topSlot() != null && !busy);
        setSlotEditButtonState(editBottomTnoButton, fourBySixWorkflow.bottomSlot() != null && !busy);
        updatePrintButtonState();
        if (!message.isEmpty()) setStatusNeutral(message);
    }

    private void showError(String message) {
        setBusy(false, message);
        setStatusError(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setStatusNeutral(String message) {
        statusText.setText(message);
        statusText.setBackgroundResource(R.drawable.soft_background);
        statusText.setTextColor(getColor(R.color.brand_dark));
        setSystemStatusBar(getColor(R.color.background), true);
    }

    private void setStatusSuccess(String message) {
        statusText.setText(message);
        statusText.setBackgroundResource(R.drawable.status_success_background);
        statusText.setTextColor(Color.WHITE);
        setSystemStatusBar(getColor(R.color.status_success), false);
    }

    private void setStatusError(String message) {
        statusText.setText(message);
        statusText.setBackgroundResource(R.drawable.status_error_background);
        statusText.setTextColor(Color.WHITE);
        setSystemStatusBar(getColor(R.color.status_error), false);
    }

    private void setSystemStatusBar(int color, boolean useDarkIcons) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(color);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (useDarkIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(pendingQrUpdate);
        if (parcelScanController != null) parcelScanController.close();
        super.onDestroy();
    }

    private boolean isActivityUsable() {
        return !destroyed && !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }
}
