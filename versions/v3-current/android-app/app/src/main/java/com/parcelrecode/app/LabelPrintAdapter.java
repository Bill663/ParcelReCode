package com.parcelrecode.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.pdf.PrintedPdfDocument;

import java.io.FileOutputStream;

public final class LabelPrintAdapter extends PrintDocumentAdapter {
    public enum LayoutMode {
        SINGLE_2X2,
        DUAL_4X6
    }

    private final Context context;
    private final Bitmap qrBitmap;
    private final String tracking;
    private final String payload;
    private final Bitmap bottomQrBitmap;
    private final String bottomPayload;
    private final LayoutMode layoutMode;
    private PrintAttributes attributes;

    public LabelPrintAdapter(
            Context context,
            Bitmap qrBitmap,
            String tracking,
            String payload,
            Bitmap bottomQrBitmap,
            String bottomPayload,
            LayoutMode layoutMode
    ) {
        this.context = context;
        this.qrBitmap = qrBitmap;
        this.tracking = tracking;
        this.payload = payload;
        this.bottomQrBitmap = bottomQrBitmap;
        this.bottomPayload = bottomPayload;
        this.layoutMode = layoutMode;
    }

    @Override
    public void onLayout(
            PrintAttributes oldAttributes,
            PrintAttributes newAttributes,
            CancellationSignal cancellationSignal,
            LayoutResultCallback callback,
            Bundle extras
    ) {
        attributes = newAttributes;
        if (cancellationSignal.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        PrintDocumentInfo info = new PrintDocumentInfo.Builder("parcel-label.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build();
        callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
    }

    @Override
    public void onWrite(
            PageRange[] pages,
            ParcelFileDescriptor destination,
            CancellationSignal cancellationSignal,
            WriteResultCallback callback
    ) {
        PrintedPdfDocument document = new PrintedPdfDocument(context, attributes);
        try {
            android.graphics.pdf.PdfDocument.Page page = document.startPage(0);
            drawLabel(page.getCanvas(), page.getInfo().getPageWidth(), page.getInfo().getPageHeight());
            document.finishPage(page);

            if (cancellationSignal.isCanceled()) {
                callback.onWriteCancelled();
                return;
            }
            try (FileOutputStream output = new FileOutputStream(destination.getFileDescriptor())) {
                document.writeTo(output);
            }
            callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
        } catch (Exception error) {
            callback.onWriteFailed(error.getMessage());
        } finally {
            document.close();
        }
    }

    private void drawLabel(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        if (layoutMode == LayoutMode.DUAL_4X6) {
            drawDual4x6Label(canvas, paint, width, height);
            return;
        }

        drawSingle2x2Label(canvas, paint, width, height);
    }

    private void drawSingle2x2Label(Canvas canvas, Paint paint, int width, int height) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        paint.setTextSize(width * 0.055f);
        canvas.drawText(context.getString(R.string.tracking_qr_title), width / 2f, height * 0.09f, paint);

        float qrSize = Math.min(width, height) * 0.64f;
        float left = (width - qrSize) / 2f;
        float top = height * 0.13f;
        canvas.drawBitmap(qrBitmap, null, new RectF(left, top, left + qrSize, top + qrSize), paint);

        paint.setTextSize(width * 0.046f);
        canvas.drawText(tracking, width / 2f, height * 0.84f, paint);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        paint.setTextSize(width * 0.025f);
        canvas.drawText(payload, width / 2f, height * 0.91f, paint);
    }

    private void drawDual4x6Label(Canvas canvas, Paint paint, int width, int height) {
        float qrSize = Math.min(width * 0.48f, height * 0.28f);
        float topQrTop = 0f;
        float bottomQrTop = height - qrSize;
        drawCornerQr(canvas, paint, qrBitmap, 0f, topQrTop, qrSize);
        drawCornerQr(canvas, paint, bottomQrBitmap, 0f, bottomQrTop, qrSize);

        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        drawPayloadText(canvas, paint, payload, 0f, qrSize + width * 0.05f, qrSize);
        drawPayloadText(canvas, paint, bottomPayload, 0f, bottomQrTop - width * 0.025f, qrSize);
    }

    private void drawCornerQr(Canvas canvas, Paint paint, Bitmap bitmap, float left, float top, float size) {
        if (bitmap == null || bitmap.isRecycled()) return;
        canvas.drawBitmap(bitmap, null, new RectF(left, top, left + size, top + size), paint);
    }

    private void drawPayloadText(Canvas canvas, Paint paint, String value, float left, float baseline, float maxWidth) {
        if (value == null || value.isEmpty()) return;
        float textSize = maxWidth * 0.058f;
        float minTextSize = maxWidth * 0.033f;
        paint.setTextSize(textSize);
        while (paint.measureText(value) > maxWidth && textSize > minTextSize) {
            textSize -= 1f;
            paint.setTextSize(textSize);
        }
        canvas.drawText(value, left + maxWidth / 2f, baseline, paint);
    }
}
