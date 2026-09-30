package com.parcelrecode.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ParcelRecognizer parcelRecognizer;

    private Button takePhotoButton;
    private Button choosePhotoButton;
    private Button printButton;
    private ImageView photoPreview;
    private ImageView qrPreview;
    private TextView statusText;
    private TextView carrierText;
    private TextView qrPayloadText;
    private EditText trackingInput;
    private EditText zipInput;
    private LinearLayout uspsZipGroup;
    private Spinner languageSpinner;
    private Spinner carrierSpinner;

    private Uri pendingCameraUri;
    private TrackingRules.Candidate currentCandidate;
    private Bitmap currentQrBitmap;
    private String currentPayload;
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
    }

    private void bindViews() {
        takePhotoButton = findViewById(R.id.takePhotoButton);
        choosePhotoButton = findViewById(R.id.choosePhotoButton);
        printButton = findViewById(R.id.printButton);
        photoPreview = findViewById(R.id.photoPreview);
        qrPreview = findViewById(R.id.qrPreview);
        statusText = findViewById(R.id.statusText);
        carrierText = findViewById(R.id.carrierText);
        qrPayloadText = findViewById(R.id.qrPayloadText);
        trackingInput = findViewById(R.id.trackingInput);
        zipInput = findViewById(R.id.zipInput);
        uspsZipGroup = findViewById(R.id.uspsZipGroup);
        languageSpinner = findViewById(R.id.languageSpinner);
        carrierSpinner = findViewById(R.id.carrierSpinner);
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

        Intent intent = createCameraIntent();
        try {
            startActivityForResult(intent, REQUEST_CAPTURE);
        } catch (Exception error) {
            startActivityForResult(createInternalCameraIntent(), REQUEST_CAPTURE);
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

    private Intent createInternalCameraIntent() {
        Intent intent = new Intent(this, CameraCaptureActivity.class);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
        return intent;
    }

    private void seedEmbeddedOpenCameraPreferences() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(PreferenceKeys.FirstTimePreferenceKey, true)
                .putBoolean(PreferenceKeys.ShowWhatsNewPreferenceKey, false)
                .putInt(PreferenceKeys.LatestVersionPreferenceKey, Integer.MAX_VALUE)
                .putString(PreferenceKeys.getFlashPreferenceKey(0), "flash_on")
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
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK);
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
            try {
                Bitmap bitmap = loadBitmap(uri, 2560);
                TrackingRules.Candidate candidate = parcelRecognizer.recognize(bitmap);

                runOnUiThread(() -> {
                    if (!isActivityUsable()) return;
                    photoPreview.setImageBitmap(bitmap);
                    applyCandidate(candidate);
                });
            } catch (Exception error) {
                Log.e(LOG_TAG, "Photo processing failed", error);
                runOnUiThread(() -> {
                    if (isActivityUsable()) showError(getString(R.string.could_not_read_photo));
                });
            }
        });
    }

    private Bitmap loadBitmap(Uri uri, int maxSide) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return loadLegacyBitmap(uri, maxSide);
        }
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
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
            currentCandidate = null;
            carrierText.setText(R.string.not_detected);
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
        statusText.setText(getString(R.string.detected_confirm_message, candidate.carrier));
        statusText.setTextColor(getColor(R.color.brand_dark));
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
            printButton.setEnabled(false);
            return;
        }

        try {
            currentQrBitmap = createQrBitmap(currentPayload, 720);
            qrPreview.setImageBitmap(currentQrBitmap);
            qrPayloadText.setText(currentPayload);
            printButton.setEnabled(true);
        } catch (Exception error) {
            showError(getString(R.string.could_not_generate_qr));
        }
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
        if (currentQrBitmap == null || currentPayload == null) return;
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        PrintAttributes.MediaSize size = new PrintAttributes.MediaSize(
                "PARCEL_2X2",
                getString(R.string.label_media_name),
                2000,
                2000
        );
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
                new LabelPrintAdapter(this, currentQrBitmap, trackingInput.getText().toString(), currentPayload),
                attributes
        );
    }

    private void setBusy(boolean busy, String message) {
        takePhotoButton.setEnabled(!busy);
        choosePhotoButton.setEnabled(!busy);
        if (!message.isEmpty()) statusText.setText(message);
    }

    private void showError(String message) {
        setBusy(false, message);
        statusText.setTextColor(getColor(R.color.danger));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
