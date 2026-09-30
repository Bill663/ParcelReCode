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
import java.io.IOException;

public final class LabelPrintAdapter extends PrintDocumentAdapter {
    private final Context context;
    private final Bitmap qrBitmap;
    private final String tracking;
    private final String payload;
    private PrintAttributes attributes;

    public LabelPrintAdapter(Context context, Bitmap qrBitmap, String tracking, String payload) {
        this.context = context;
        this.qrBitmap = qrBitmap;
        this.tracking = tracking;
        this.payload = payload;
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
        } catch (IOException error) {
            callback.onWriteFailed(error.getMessage());
        } finally {
            document.close();
        }
    }

    private void drawLabel(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
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
}
