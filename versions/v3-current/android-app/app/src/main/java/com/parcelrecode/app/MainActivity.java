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
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import net.sourceforge.opencamera.PreferenceKeys;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "ParcelReLabel";
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_PICK = 1002;
    private static final int REQUEST_STORAGE_PERMISSION = 1003;
    private static final int REQUEST_CAMERA_PERMISSION = 1004;
    private static final String EMBEDDED_OPEN_CAMERA_ACTIVITY = "net.sourceforge.opencamera.MainActivity";
    private static final String LANGUAGE_PREFERENCES = "language_preferences";
    private static final String LANGUAGE_KEY = "language";
    private static final String APP_PREFERENCES = "parcel_recode_preferences";
    private static final String LABEL_LAYOUT_KEY = "label_layout";
    private static final String ACCESSIBILITY_MODE_KEY = "accessibility_mode";
    private static final String DEBUG_PHOTO_PATH_EXTRA = "com.parcelrecode.app.DEBUG_PHOTO_PATH";
    private static final int LABEL_LAYOUT_SINGLE_2X2 = 0;
    private static final int LABEL_LAYOUT_DUAL_4X6 = 1;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(newScanThreadFactory());
    private ParcelRecognizer parcelRecognizer;

    private Button takePhotoButton;
    private Button choosePhotoButton;
    private Button printButton;
    private Button clear4x6SlotsButton;
    private Button editTopTnoButton;
    private Button editBottomTnoButton;
    private Button accessibilityModeButton;
    private ImageView photoPreview;
    private ImageView qrPreview;
    private TextView statusText;
    private TextView carrierText;
    private TextView qrPayloadText;
    private TextView topSlotPayloadText;
    private TextView bottomSlotPayloadText;
    private EditText trackingInput;
    private EditText zipInput;
    private LinearLayout uspsZipGroup;
    private LinearLayout bottomQrGroup;
    private Spinner languageSpinner;
    private Spinner carrierSpinner;
    private Spinner labelLayoutSpinner;

    private Uri pendingCameraUri;
    private TrackingRules.Candidate currentCandidate;
    private Bitmap currentQrBitmap;
    private String currentPayload;
    private Bitmap topSlotQrBitmap;
    private Bitmap bottomSlotQrBitmap;
    private String topSlotPayload;
    private String bottomSlotPayload;
    private boolean clear4x6OnNextScan;
    private boolean accessibilityModeEnabled;
    private boolean updatingFields;
    private boolean destroyed;

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
        configureLanguageSelector();
        parcelRecognizer = new ParcelRecognizer();
        wireEvents();
        handleDebugPhotoIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDebugPhotoIntent(intent);
    }

    private void bindViews() {
        takePhotoButton = findViewById(R.id.takePhotoButton);
        choosePhotoButton = findViewById(R.id.choosePhotoButton);
        printButton = findViewById(R.id.printButton);
        clear4x6SlotsButton = findViewById(R.id.clear4x6SlotsButton);
        editTopTnoButton = findViewById(R.id.editTopTnoButton);
        editBottomTnoButton = findViewById(R.id.editBottomTnoButton);
        accessibilityModeButton = findViewById(R.id.accessibilityModeButton);
        photoPreview = findViewById(R.id.photoPreview);
        qrPreview = findViewById(R.id.qrPreview);
        statusText = findViewById(R.id.statusText);
        carrierText = findViewById(R.id.carrierText);
        qrPayloadText = findViewById(R.id.qrPayloadText);
        topSlotPayloadText = findViewById(R.id.topSlotPayloadText);
        bottomSlotPayloadText = findViewById(R.id.bottomSlotPayloadText);
        trackingInput = findViewById(R.id.trackingInput);
        zipInput = findViewById(R.id.zipInput);
        uspsZipGroup = findViewById(R.id.uspsZipGroup);
        bottomQrGroup = findViewById(R.id.bottomQrGroup);
        languageSpinner = findViewById(R.id.languageSpinner);
        carrierSpinner = findViewById(R.id.carrierSpinner);
        labelLayoutSpinner = findViewById(R.id.labelLayoutSpinner);
    }

    private void configureLanguageSelector() {
        String activeLanguage = preferredLanguage(this);
        languageSpinner.setSelection("zh".equals(activeLanguage) ? 1 : 0, false);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLanguage = position == 1 ? "zh" : "en";
                if (selectedLanguage.equals(preferredLanguage(MainActivity.this))) return;
                getSharedPreferences(LANGUAGE_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putString(LANGUAGE_KEY, selectedLanguage)
                        .apply();
                recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private static String preferredLanguage(Context context) {
        String saved = context.getSharedPreferences(LANGUAGE_PREFERENCES, MODE_PRIVATE)
                .getString(LANGUAGE_KEY, "");
        if (!saved.isEmpty()) return saved;
        return "zh".equals(Locale.getDefault().getLanguage()) ? "zh" : "en";
    }

    private void wireEvents() {
        takePhotoButton.setOnClickListener(view -> launchCamera());
        choosePhotoButton.setOnClickListener(view -> launchPicker());
        printButton.setOnClickListener(view -> printLabel());
        clear4x6SlotsButton.setOnClickListener(view -> clear4x6Slots());
        editTopTnoButton.setOnClickListener(view -> edit4x6Slot(true));
        editBottomTnoButton.setOnClickListener(view -> edit4x6Slot(false));
        accessibilityModeEnabled = savedAccessibilityMode();
        accessibilityModeButton.setOnClickListener(view -> {
            accessibilityModeEnabled = !accessibilityModeEnabled;
            saveAccessibilityMode(accessibilityModeEnabled);
            applyAccessibilityMode();
        });

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!updatingFields) updateQrFromFields();
            }
            @Override public void afterTextChanged(Editable editable) {}
        };
        trackingInput.addTextChangedListener(watcher);
        zipInput.addTextChangedListener(watcher);
        trackingInput.setOnClickListener(view -> showFallbackKeyboardIfNeeded(trackingInput, false));
        zipInput.setOnClickListener(view -> showFallbackKeyboardIfNeeded(zipInput, true));
        trackingInput.setOnTouchListener((view, event) ->
                openFallbackKeyboardOnTouch(view, event));
        zipInput.setOnTouchListener((view, event) ->
                openFallbackKeyboardOnTouch(view, event));
        carrierSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!updatingFields) updateQrFromFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        labelLayoutSpinner.setSelection(savedLabelLayout(), false);
        labelLayoutSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                saveLabelLayout(position);
                updatePrintLayoutMode();
                if (!updatingFields) updateQrFromFields();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        updatePrintLayoutMode();
        applyAccessibilityMode();
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

    private void applyAccessibilityMode() {
        if (accessibilityModeButton == null) return;

        accessibilityModeButton.setText(accessibilityModeEnabled
                ? R.string.accessibility_mode_on
                : R.string.accessibility_mode_off);
        accessibilityModeButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                getColor(accessibilityModeEnabled ? R.color.brand : R.color.surface)));
        accessibilityModeButton.setTextColor(getColor(accessibilityModeEnabled
                ? android.R.color.white
                : R.color.ink));

        carrierText.setTextSize(accessibilityModeEnabled ? 22 : 18);
        trackingInput.setTextSize(accessibilityModeEnabled ? 24 : 18);
        trackingInput.setMinHeight(dp(accessibilityModeEnabled ? 64 : 52));
        zipInput.setTextSize(accessibilityModeEnabled ? 24 : 17);
        zipInput.setMinHeight(dp(accessibilityModeEnabled ? 58 : 48));
        qrPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        topSlotPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        bottomSlotPayloadText.setTextSize(accessibilityModeEnabled ? 20 : 14);
        statusText.setTextSize(accessibilityModeEnabled ? 17 : 15);
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
                .setTitle(numbersOnly ? R.string.edit_zip_code : R.string.edit_tracking_number)
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

    private void launchCamera() {
        prepareForNewScanRequest();
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
                .putString(PreferenceKeys.getFlashPreferenceKey(0), "flash_on")
                .putString(PreferenceKeys.ExposurePreferenceKey, "-1")
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
                    launchCamera();
                } else {
                    showError(getString(R.string.camera_permission_required));
                }
            }
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            showError(getString(R.string.storage_permission_required));
        }
    }

    private void launchPicker() {
        prepareForNewScanRequest();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK);
    }

    private void handleDebugPhotoIntent(Intent intent) {
        if (intent == null || !isDebuggable()) return;
        String path = intent.getStringExtra(DEBUG_PHOTO_PATH_EXTRA);
        if (path == null || path.trim().isEmpty()) return;
        prepareForNewScanRequest();
        processPhoto(Uri.fromFile(new File(path)));
    }

    private void prepareForNewScanRequest() {
        if (!clear4x6OnNextScan || !isDual4x6Selected()) return;
        clear4x6Slots();
        clearCurrentScanState();
        clear4x6OnNextScan = false;
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_CAPTURE && pendingCameraUri != null) {
                getContentResolver().delete(pendingCameraUri, null, null);
            }
            return;
        }

        Uri imageUri = requestCode == REQUEST_CAPTURE
                ? pendingCameraUri
                : data == null ? null : data.getData();
        if (imageUri != null) processPhoto(imageUri);
    }

    private void processPhoto(Uri uri) {
        setBusy(true, getString(R.string.reading_parcel_label));
        executor.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Bitmap bitmap = loadBitmap(uri, 2560);
                TrackingRules.Candidate candidate = parcelRecognizer.recognize(bitmap);
                Log.d(LOG_TAG, "Photo processed in " + (System.currentTimeMillis() - startedAt) + " ms");

                runOnUiThread(() -> {
                    if (!isActivityUsable()) return;
                    photoPreview.setImageBitmap(bitmap);
                    applyCandidate(candidate);
                });
            } catch (Exception error) {
                Log.e(LOG_TAG, "Photo processing failed", error);
                runOnUiThread(() -> {
                    if (isActivityUsable()) {
                        showError(getString(R.string.could_not_read_photo));
                    }
                });
            }
        });
    }

    private static ThreadFactory newScanThreadFactory() {
        return runnable -> new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
            runnable.run();
        }, "ParcelScan");
    }

    private Bitmap loadBitmap(Uri uri, int maxSide) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return loadLegacyBitmap(uri, maxSide);
        }
        ImageDecoder.Source source = "file".equals(uri.getScheme())
                ? ImageDecoder.createSource(new File(uri.getPath()))
                : ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            int largest = Math.max(width, height);
            if (largest > maxSide) {
                float scale = maxSide / (float) largest;
                decoder.setTargetSize(Math.round(width * scale), Math.round(height * scale));
            }
        });
    }

    private Bitmap loadLegacyBitmap(Uri uri, int maxSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSide * 2) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("Bitmap decode failed");

        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largest > maxSide) {
            float scale = maxSide / (float) largest;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.round(bitmap.getWidth() * scale),
                    Math.round(bitmap.getHeight() * scale),
                    true
            );
            bitmap.recycle();
            bitmap = scaled;
        }

        int rotation = readExifRotation(uri);
        if (rotation == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private int readExifRotation(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return 0;
            int orientation = new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
            return 0;
        }
        return 0;
    }

    private void applyCandidate(TrackingRules.Candidate candidate) {
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
        zipInput.setText(candidate.zipCode);
        updatingFields = false;
        updateQrFromFields();
        saveCurrentQrTo4x6SlotIfNeeded();
        if (!isDual4x6Selected()) {
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
        uspsZipGroup.setVisibility("usps".equals(company) ? View.VISIBLE : View.GONE);

        String machine = currentCandidate != null && tracking.equals(currentCandidate.tracking)
                ? currentCandidate.machineValue
                : "";
        currentPayload = TrackingRules.formatQrPayload(tracking, zip, machine, selectedCompany);
        if (currentPayload == null) {
            currentQrBitmap = null;
            qrPreview.setImageDrawable(null);
            qrPayloadText.setText("usps".equals(company)
                    ? R.string.enter_destination_zip
                    : R.string.waiting_for_valid_tracking);
            updatePrintButtonState();
            return;
        }

        try {
            currentQrBitmap = createQrBitmap(currentPayload, 720);
            qrPreview.setImageBitmap(currentQrBitmap);
            qrPayloadText.setText(currentPayload);
            updatePrintButtonState();
        } catch (Exception error) {
            showError(getString(R.string.could_not_generate_qr));
        }
    }

    private boolean isDual4x6Selected() {
        return labelLayoutSpinner != null
                && labelLayoutSpinner.getSelectedItemPosition() == LABEL_LAYOUT_DUAL_4X6;
    }

    private void updatePrintLayoutMode() {
        if (bottomQrGroup != null) {
            bottomQrGroup.setVisibility(isDual4x6Selected() ? View.VISIBLE : View.GONE);
        }
        if (isDual4x6Selected() && topSlotPayload == null && currentQrBitmap != null && currentPayload != null) {
            saveCurrentQrTo4x6SlotIfNeeded();
        }
        update4x6SlotText();
        updatePrintButtonState();
    }

    private void saveCurrentQrTo4x6SlotIfNeeded() {
        if (!isDual4x6Selected() || currentQrBitmap == null || currentPayload == null) return;

        if (topSlotPayload == null) {
            topSlotQrBitmap = currentQrBitmap;
            topSlotPayload = currentPayload;
            setStatusSuccess(getString(R.string.scan_second_label));
        } else if (bottomSlotPayload == null) {
            bottomSlotQrBitmap = currentQrBitmap;
            bottomSlotPayload = currentPayload;
            setStatusSuccess(getString(R.string.four_by_six_ready));
        }
        update4x6SlotText();
        updatePrintButtonState();
    }

    private void update4x6SlotText() {
        if (topSlotPayloadText == null || bottomSlotPayloadText == null) return;
        topSlotPayloadText.setText(topSlotPayload == null ? getString(R.string.empty_qr_slot) : topSlotPayload);
        bottomSlotPayloadText.setText(bottomSlotPayload == null ? getString(R.string.empty_qr_slot) : bottomSlotPayload);
        setSlotEditButtonState(editTopTnoButton, topSlotPayload != null);
        setSlotEditButtonState(editBottomTnoButton, bottomSlotPayload != null);
    }

    private void setSlotEditButtonState(Button button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void clear4x6Slots() {
        topSlotQrBitmap = null;
        bottomSlotQrBitmap = null;
        topSlotPayload = null;
        bottomSlotPayload = null;
        clear4x6OnNextScan = false;
        update4x6SlotText();
        updatePrintButtonState();
        setStatusNeutral(getString(R.string.ready_for_photo));
    }

    private void clearCurrentScanState() {
        currentCandidate = null;
        currentQrBitmap = null;
        currentPayload = null;
        updatingFields = true;
        carrierText.setText(R.string.not_detected);
        carrierSpinner.setSelection(0, false);
        trackingInput.setText("");
        zipInput.setText("");
        updatingFields = false;
        uspsZipGroup.setVisibility(View.GONE);
        qrPayloadText.setText(R.string.waiting_for_tracking);
        qrPreview.setImageDrawable(null);
        updatePrintButtonState();
    }

    private void edit4x6Slot(boolean topSlot) {
        String activePayload = topSlot ? topSlotPayload : bottomSlotPayload;
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
                    Bitmap editedQrBitmap = createQrBitmap(editedPayload, 720);
                    if (topSlot) {
                        topSlotPayload = editedPayload;
                        topSlotQrBitmap = editedQrBitmap;
                    } else {
                        bottomSlotPayload = editedPayload;
                        bottomSlotQrBitmap = editedQrBitmap;
                    }
                    update4x6SlotText();
                    updatePrintButtonState();
                    dialog.dismiss();
                } catch (Exception error) {
                    input.setError(getString(R.string.could_not_generate_qr));
                }
            });
        });
        dialog.show();
    }

    private void updatePrintButtonState() {
        if (isDual4x6Selected()) {
            printButton.setEnabled(topSlotQrBitmap != null && bottomSlotQrBitmap != null);
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
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private void printLabel() {
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        boolean dual4x6 = isDual4x6Selected();
        if (dual4x6) {
            if (topSlotQrBitmap == null || bottomSlotQrBitmap == null) return;
        } else if (currentQrBitmap == null || currentPayload == null) {
            return;
        }
        PrintAttributes.MediaSize size = new PrintAttributes.MediaSize(
                dual4x6 ? "PARCEL_4X6" : "PARCEL_2X2",
                getString(dual4x6 ? R.string.label_media_name_4x6 : R.string.label_media_name),
                dual4x6 ? 4000 : 2000,
                dual4x6 ? 6000 : 2000
        );
        LabelPrintAdapter.LayoutMode layoutMode = dual4x6
                ? LabelPrintAdapter.LayoutMode.DUAL_4X6
                : LabelPrintAdapter.LayoutMode.SINGLE_2X2;
        PrintAttributes attributes = new PrintAttributes.Builder()
                .setMediaSize(size)
                .setResolution(new PrintAttributes.Resolution(
                        "parcel_300",
                        getString(R.string.print_resolution_name),
                        300,
                        300
                ))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        manager.print(
                getString(R.string.print_job_name, trackingInput.getText()),
                new LabelPrintAdapter(
                        this,
                        dual4x6 ? topSlotQrBitmap : currentQrBitmap,
                        dual4x6 ? "" : trackingInput.getText().toString(),
                        dual4x6 ? topSlotPayload : currentPayload,
                        dual4x6 ? bottomSlotQrBitmap : currentQrBitmap,
                        dual4x6 ? bottomSlotPayload : currentPayload,
                        layoutMode),
                attributes
        );
        if (dual4x6) {
            clear4x6OnNextScan = true;
        }
    }

    private void setBusy(boolean busy, String message) {
        takePhotoButton.setEnabled(!busy);
        choosePhotoButton.setEnabled(!busy);
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
        if (parcelRecognizer != null) parcelRecognizer.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    private boolean isActivityUsable() {
        return !destroyed && !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }
}
